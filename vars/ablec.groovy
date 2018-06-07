
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
    echo "Checking out our own copy of ableC"

    checkout([$class: 'GitSCM',
              branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ableC'],
                           [$class: 'CleanCheckout']],
              submoduleCfg: [],
              userRemoteConfigs: [[url: 'https://github.com/melt-umn/ableC.git']]])

    // TODO: we *might* wish to melt.annotate if we're checking out a *branch* of ablec, figure out how to check? and maybe consider whether we want that?

    return "${env.WORKSPACE}/ableC/"
  }

  echo "Using existing ableC workspace: ${params.ABLEC_BASE}"
  melt.annotate('Custom AbleC.')
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
// Obtain a path to Silver-ableC to use to build this extension
//
// e.g. def silver_ablec_path = ablec.resolveSilverAbleC()
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def resolveSilverAbleC(ablec_base) {

  if (params.SILVER_ABLEC_BASE == 'silver-ableC') {
    echo "Checking out and building our own copy of silver-ableC"

    // TODO: we *might* wish to melt.annotate if we're checking out a *branch* of silver-ableC, figure out how to check? and maybe consider whether we want that?

    def extensions = [
      "silver-ableC",
      "ableC-closure",
      "ableC-refcount-closure",
      "ableC-templating"
    ]
    for (ext in extensions) {
      checkoutExtension(ext)
    }
    
    def newenv = melt.getSilverEnv() + [
      "ABLEC_BASE=${ablec_base}",
      "EXTS_BASE=${env.WORKSPACE}/extensions"
    ]

    withEnv(newenv) {
      dir("${env.WORKSPACE}/extensions/silver-ableC") {
        sh "./bootstrap-compile"
      }
    }

    return "${env.WORKSPACE}/extensions/silver-ableC/"
  }
  
  // We assume that the extension dependancies of silver-ableC are already checked out as well
  echo "Using existing silver-ableC workspace: ${params.SILVER_ABLEC_BASE}"
  melt.annotate('Custom Silver-ableC.')
  
  return params.SILVER_ABLEC_BASE
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
// ./generated/              (empty)
// ./ableC/                  (if no ABLEC_BASE parameter is set)
// ./extensions/silver-ableC (if requested no SILVER_ABLEC_BASE parameter is set)
// ./extensions/deps         (ditto for silver-ableC dependencies)
// ./extensions/name         (where this extension is checked out)
// ./extensions/more         (if given)
//
def prepareWorkspace(name, extensions=[], hasSilverAbleC=false) {
  
  // Clean Silver-generated files from previous builds in this workspace
  melt.clearGenerated()

  // Get AbleC (may grab generated files, too)
  def ablec_base = resolveHost()
  
  // Get Silver-ableC
  def silver_ablec_base = hasSilverAbleC? resolveSilverAbleC(ablec_base) : null
  
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

  def newenv = melt.getSilverEnv() + [
    "ABLEC_BASE=${ablec_base}",
    "EXTS_BASE=${env.WORKSPACE}/extensions",
    // libcord, libgc, cilk headers:
    "C_INCLUDE_PATH=/project/melt/Software/ext-libs/usr/local/include",
    "LIBRARY_PATH=/project/melt/Software/ext-libs/usr/local/lib"
  ] + (hasSilverAbleC? ["PATH+silver-ableC=${params.SILVER_ABLEC_BASE}/support/bin/"] : [])
  
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
  internalBuildExtension(extension_name, extensions, false, false)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build with a C library. (see: ableC-lib-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
// extensions: the other extensions this extension depends upon
//
def buildLibraryExtension(extension_name, extensions=[]) {
  internalBuildExtension(extension_name, extensions, true, false)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build requiring silver-ableC. (see: ableC-closure)
//
// extension_name: the name of this extension, the 'scm' object should reference
// extensions: the other extensions this extension depends upon
//
def buildSilverAbleCExtension(extension_name, extensions=[]) {
  internalBuildExtension(extension_name, extensions, false, true)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build with a C library, requiring silver-ableC.
// (see: ableC-nondeterministic-search)
//
// extension_name: the name of this extension, the 'scm' object should reference
// extensions: the other extensions this extension depends upon
//
def buildLibrarySilverAbleCExtension(extension_name, extensions=[]) {
  internalBuildExtension(extension_name, extensions, true, true)
}

////////////////////////////////////////////////////////////////////////////////
//
// Do the above.
//
def internalBuildExtension(extension_name, extensions, hasLibrary, hasSilverAbleC) {

  melt.setProperties(silverBase: true, ablecBase: true, silverAblecBase: hasSilverAbleC)

  def isFastBuild = (params.ABLEC_GEN != 'no')

  melt.trynode(extension_name) {

    def newenv // visible scope to all stages

    stage ("Build") {

      newenv = prepareWorkspace(extension_name, extensions, hasSilverAbleC)

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
}

