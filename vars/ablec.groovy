
import jenkins.model.Jenkins

// Location where we dump stable artifacts: jars, tarballs
//@groovy.transform.Field
//ARTIFACTS = '/export/scratch/melt-jenkins/custom-stable-dump'


////////////////////////////////////////////////////////////////////////////////
//
// Obtain a path to AbleC to use to build this extension
//
// e.g. def ablec_base = ablec.resolveAbleC()
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def resolveAbleC() {

  if (params.ABLEC_BASE == 'ableC') {
    echo "Checking out our own copy of ableC (branch ${env.BRANCH_NAME})"

    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
        doGenerateSubmoduleConfigurations: false,
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'ableC'],
                     [$class: 'CleanCheckout']],
        submoduleCfg: [],
        userRemoteConfigs: [[url: 'https://github.com/melt-umn/ableC.git']]])

    // TODO: we *might* wish to melt.annotate if we're checking out a *branch* of ablec, figure out how to check? and maybe consider whether we want that?

    return "${env.WORKSPACE}/ableC"
  }

  echo "Using existing ableC workspace: ${params.ABLEC_BASE}"
  melt.annotate('Custom AbleC.')

  return params.ABLEC_BASE
}

////////////////////////////////////////////////////////////////////////////////
//
// Obtain a path to Silver-ableC to use to build this extension
//
// e.g. def silver_ablec_base = ablec.resolveSilverAbleC(silver_base, ablec_base)
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def resolveSilverAbleC(silver_base, ablec_base) {

  if (params.SILVER_ABLEC_BASE == 'silver-ableC') {
    echo "Checking out our own copy of silver-ableC"

    checkoutExtension("silver-ableC")
    // TODO: we *might* wish to melt.annotate if we're checking out a *branch* of silver-ableC, figure out how to check? and maybe consider whether we want that?

    // Try to obtain jars from previous builds of this branch of silver-ableC
    echo "Trying to get jars from silver-ableC branch ${env.BRANCH_NAME}"
    String branchJob = "/melt-umn/silver-ableC/${hudson.Util.rawEncode(env.BRANCH_NAME)}"
    try {
      // If the last build has artifacts, use those.
      dir("${env.WORKSPACE}/extensions/silver-ableC") {
        copyArtifacts(projectName: branchJob, selector: lastCompleted())
      }
      melt.annotate("Silver-ableC jar from branch (prev).")
    } catch (hudson.AbortException exc2) {
      try {
        // If there is a last successful build, use those.
        dir("${env.WORKSPACE}/extensions/silver-ableC") {
          copyArtifacts(projectName: branchJob, selector: lastSuccessful())
        }
        melt.annotate("Silver-ableC jar from branch (successful).")
      } catch (hudson.AbortException exc3) {
        // That's okay, just go build it ourselves.
        echo "Couldn't find Silver-ableC jar from branch, building from scratch"

        // Check out ableC extensions included in default silver-ableC composition
        def extensions = [
          "ableC-closure",
          "ableC-refcount-closure",
          "ableC-templating",
          "ableC-string",
          "ableC-constructor",
          "ableC-algebraic-data-types",
          "ableC-template-algebraic-data-types"
        ]
        for (ext in extensions) {
          checkoutExtension(ext)
        }

        // Build it!
        def newenv = silver.getSilverEnv(silver_base) + [
          "ABLEC_BASE=${ablec_base}",
          "EXTS_BASE=${env.WORKSPACE}/extensions"
        ]
        withEnv(newenv) {
          dir("${env.WORKSPACE}/extensions/silver-ableC") {
            sh "./bootstrap-compile"
          }
        }
        
        melt.annotate("Silver-ableC jars from branch (fresh).")
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
def checkoutExtension(ext, url_base="https://github.com/melt-umn") {
  echo "Checking out our own copy of extension ${ext} (branch ${env.BRANCH_NAME})"

  checkout([
    $class: 'GitSCM',
    branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
    doGenerateSubmoduleConfigurations: false,
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "extensions/${ext}"],
                 [$class: 'CleanCheckout']],
    submoduleCfg: [],
    userRemoteConfigs: [[url: "${url_base}/${ext}.git"]]])

}

////////////////////////////////////////////////////////////////////////////////
//
// Get set up to build. Creates:
//
// ./generated/              (empty)
// ./ableC/                  (if no ABLEC_BASE parameter is set)
// ./extensions/silver-ableC (if requested and no SILVER_ABLEC_BASE parameter is set)
// ./extensions/deps         (ditto for silver-ableC dependencies, if silver-ableC jars weren't available)
// ./extensions/name         (where this extension is checked out)
// ./extensions/more         (if given)
//
def prepareWorkspace(name, extensions=[], usesSilverAbleC=false) {
  
  // Clean Silver-generated files from previous builds in this workspace
  melt.clearGenerated()

  // Get Silver
  def silver_base = silver.resolveSilver()

  // Get AbleC
  def ablec_base = resolveAbleC()
  
  // Get Silver-ableC
  def silver_ablec_base = usesSilverAbleC? resolveSilverAbleC(silver_base, ablec_base) : null
  
  // Get this extension
  checkout([
      $class: 'GitSCM',
      branches: scm.branches,
      doGenerateSubmoduleConfigurations: scm.doGenerateSubmoduleConfigurations,
      extensions: [
        [$class: 'RelativeTargetDirectory', relativeTargetDir: "extensions/${name}"],
        [$class: 'CleanCheckout']
      ],
      submoduleCfg: scm.submoduleCfg,
      userRemoteConfigs: scm.userRemoteConfigs])

  // Get the other extensions, preferring same branch name over develop
  for (ext in extensions) {
    checkoutExtension(ext)
  }

  def newenv = silver.getSilverEnv(silver_base) + [
    "ABLEC_BASE=${ablec_base}",
    "EXTS_BASE=${env.WORKSPACE}/extensions",
    // libcord, libgc, cilk headers:
    "C_INCLUDE_PATH=/project/melt/Software/ext-libs/usr/local/include",
    "LIBRARY_PATH=/project/melt/Software/ext-libs/usr/local/lib"
  ]

  if (usesSilverAbleC) {
    newenv << "PATH+silver-ableC=${silver_ablec_base}/support/bin/"
  }
  if (params.ABLEC_GEN != 'no') {
    echo "Using existing ableC generated files: ${params.ABLEC_GEN}"
    newenv << "SILVER_HOST_GEN=${params.ABLEC_GEN}"
  }
  
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
def internalBuildExtension(extension_name, extensions, hasLibrary, usesSilverAbleC) {

  melt.setProperties(silverBase: true, ablecBase: true, silverAblecBase: usesSilverAbleC)

  def isFastBuild = (params.ABLEC_GEN != 'no')

  melt.trynode(extension_name) {

    def newenv // visible scope to all stages

    stage ("Build") {

      newenv = prepareWorkspace(extension_name, extensions, usesSilverAbleC)

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
            sh "make analyses -j2"
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

