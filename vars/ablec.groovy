
import jenkins.model.Jenkins

// Location where we dump stable artifacts: jars, tarballs
//@groovy.transform.Field
//ARTIFACTS = '/export/scratch/melt-jenkins/custom-stable-dump'


////////////////////////////////////////////////////////////////////////////////
//
// Obtain a path to AbleC to use to build this extension
//
// e.g. def ablec_path = ablec.resolveHost()
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def resolveHost() {

  if (params.ABLEC_BASE == 'ableC') {
    echo "Checking out our own copy of ableC/develop"

    checkout([$class: 'GitSCM',
              branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ableC'],
                           [$class: 'CleanCheckout']],
              submoduleCfg: [],
              userRemoteConfigs: [[url: 'https://github.com/melt-umn/ableC.git']]])

    return pwd() + "/ableC/"
  }

  echo "Using existing ableC workspace: ${params.ABLEC_BASE}"
  if (params.ABLEC_GEN != 'no') {
    echo "Using existing ableC generated files: ${params.ABLEC_GEN}"
    sh "cp -r ${params.ABLEC_GEN}/* generated/"
    // Freshen up generated parsers so .java is newer than .copper
    sh 'find generated/ -name "Parser*java" | xargs touch'
  }

  return params.ABLEC_BASE
}

////////////////////////////////////////////////////////////////////////////////
//
// Checkout an extension into the local workspace (into extensions/)
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def checkoutExtension(ext) {

  checkout([
    $class: 'GitSCM',
    branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
    doGenerateSubmoduleConfigurations: false,
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "extensions/${ext}"],
                 [$class: 'CleanCheckout']],
    submoduleCfg: [],
    userRemoteConfigs: [[url: "https://github.com/melt-umn/${ext}.git"]]])

}

////////////////////////////////////////////////////////////////////////////////
//
// Get set up to build. Creates:
//
// ./generated/       (empty)
// ./ableC/           (if no ABLEC_BASE parameter is set)
// ./extensions/name  (where this extension is checked out)
// ./extensions/more  (if given)
//
def prepareWorkspace(name, extensions=[]) {
  
  // Clean Silver-generated files from previous builds in this workspace
  melt.clearGenerated()

  // Get AbleC (may grab generated files, too)
  def ablec_base = resolveHost()
  
  // Get this extension
  checkout([$class: 'GitSCM',
            branches: scm.branches,
            doGenerateSubmoduleConfigurations: scm.doGenerateSubmoduleConfigurations,
            extensions: [
              [$class: 'RelativeTargetDirectory', relativeTargetDir: "extensions/${name}"],
              [$class: 'CleanCheckout']
            ],
            submoduleCfg: scm.submoduleCfg,
            userRemoteConfigs: scm.userRemoteConfigs
            ])

  // Get the other extensions, preferring same branch name over develop
  for (ext in extensions) {
    checkoutExtension(ext)
  }

  def path = pwd()
  def newenv = melt.getSilverEnv() + [
    "ABLEC_BASE=${ablec_base}",
    "EXTS_BASE=${path}/extensions",
    // libcord, libgc, cilk headers:
    "C_INCLUDE_PATH=/project/melt/Software/ext-libs/usr/local/include",
    "LIBRARY_PATH=/project/melt/Software/ext-libs/usr/local/lib"
  ]
  
  return newenv
}

////////////////////////////////////////////////////////////////////////////////
//
// A normal AbleC extension build. (see: ableC-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
// extensions: the other extensions this extension depends upon
//
def buildNormalExtension(extension_name, extensions=[]) {
  internalBuildExtension(extension_name, extensions, false)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build with a C library. (see: ableC-lib-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
// extensions: the other extensions this extension depends upon
//
def buildLibraryExtension(extension_name, extensions=[]) {
  internalBuildExtension(extension_name, extensions, true)
}

////////////////////////////////////////////////////////////////////////////////
//
// Do the above.
//
def internalBuildExtension(extension_name, extensions, hasLibrary) {

  melt.setProperties(silverBase: true, ablecBase: true)

  def isFastBuild = (params.ABLEC_GEN != 'no')

  node {
  try {

    def newenv // visible scope to all stages

    stage ("Build") {

      newenv = prepareWorkspace(extension_name, extensions)

      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          if (isFastBuild) {
            echo "Fast build: doing MWDA as part of initial build"
            sh 'make "SVFLAGS=${SVFLAGS} --warn-all --warn-error" clean build'
          } else {
            sh 'make clean build'
          }
        }
      }
    }

    if (hasLibrary) {
      stage ("Libraries") {
        withEnv(newenv) {
          dir("extensions/${extension_name}") {
            sh "make libs -j8"
          }
        }
      }
    }

    stage ("Examples") {
      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          sh "make examples -j8"
        }
      }
    }

    stage ("Modular Analyses") {
      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          if (isFastBuild) {
            echo "Fast build: only doing MDA, skipping MWDA (done already)"
            sh "make mda"
          } else {
            sh "make analyses"
          }
        }
      }
    }

    stage ("Test") {
      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          if (isFastBuild) {
            echo "Fast build: copying ableC.jar into tests"
            sh "cp examples/ableC.jar tests/"
          }
          sh "make test -j8"
        }
      }
    }

    /* If we've gotten all this way with a successful build, don't take up disk space */
    melt.clearGenerated()
  }
  catch (e) {
    melt.handle(e)
  }
  finally {
    melt.notify(job: extension_name)
  }
  } // node
}

