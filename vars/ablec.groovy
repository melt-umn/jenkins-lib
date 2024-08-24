
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
    branch = melt.doesBranchExist(env.BRANCH_NAME, "ableC")? env.BRANCH_NAME : "develop"
    echo "Checking out our own copy of ableC (branch ${branch})"

    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${branch}"]],
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
  
  // We assume that the extension dependencies of silver-ableC are already checked out as well
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
  /* The *right* way of doing this, I think.  Unfourtunately this seems to be bugged?
  checkout resolveScm(
    source: git(url: "${url_base}/${ext}.git"),
    targets: [env.BRANCH_NAME, 'develop'],
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "extensions/${ext}"],
                 [$class: 'CleanCheckout']])
   */
  
  branch = melt.doesBranchExist(env.BRANCH_NAME, ext, url_base)? env.BRANCH_NAME : "develop"
  echo "Checking out our own copy of extension ${ext} (branch ${branch})"
  
  checkout([
      $class: 'GitSCM',
      branches: [[name: "*/${branch}"]],
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
def prepareWorkspace(name, usesSilverAbleC=false) {
  
  // Clean Silver-generated files from previous builds in this workspace
  melt.clearGenerated()
  
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

  // Get the dependencies of this extension from its Makefile
  def extensions = []
  dir ("extensions/${name}") {
    extensions = sh(returnStdout: true, script: "grep 'EXT_DEPS *=.*' Makefile | sed 's/EXT_DEPS *=//'").tokenize()
  }
  echo "Dependencies: ${extensions}"

  // Get the other extensions, preferring same branch name over develop
  for (ext in extensions) {
    echo "Checking out dependency: ${ext}"
    checkoutExtension(ext)
  }

  // Get Silver
  def silver_base = silver.resolveSilver()

  // Get AbleC
  def ablec_base = resolveAbleC()

  // Get Silver-ableC
  // This happens last, so that if we need to bootstrap silver-ableC
  // it doesn't get overwritten by extension checkout
  def silver_ablec_base = usesSilverAbleC? resolveSilverAbleC(silver_base, ablec_base) : null

  def newenv = silver.getSilverEnv(silver_base) + [
    "ABLEC_BASE=${ablec_base}",
    "EXTS_BASE=${env.WORKSPACE}/extensions",
    // cilk headers:
    "C_INCLUDE_PATH=/export/scratch/thirdparty/opencilk-2.0.1/lib/clang/14.0.6/include/cilk/include",
    "LIBRARY_PATH=/export/scratch/thirdparty/opencilk-2.0.1/lib/clang/14.0.6/lib/x86_64-unknown-linux-gnu",
  ]

  if (usesSilverAbleC) {
    newenv << "PATH+silver-ableC=${silver_ablec_base}/support/bin/"
  }
  def SILVER_HOST_GEN = []
  if (params.SILVER_GEN != 'no') {
    echo "Using existing Silver generated files: ${params.SILVER_GEN}"
    SILVER_HOST_GEN << "${params.SILVER_GEN}"
  }
  if (params.ABLEC_GEN != 'no') {
    echo "Using existing ableC generated files: ${params.ABLEC_GEN}"
    SILVER_HOST_GEN << "${params.ABLEC_GEN}"
  }
  newenv << "SILVER_HOST_GEN=${SILVER_HOST_GEN.join(':')}"
  
  return newenv
}

////////////////////////////////////////////////////////////////////////////////
//
// A normal AbleC extension build. (see: ableC-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
//
def buildNormalExtension(extension_name) {
  internalBuildExtension(extension_name, false, false)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build with a C library. (see: ableC-lib-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
//
def buildLibraryExtension(extension_name) {
  internalBuildExtension(extension_name, true, false)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build requiring silver-ableC. (see: ableC-closure)
//
// extension_name: the name of this extension, the 'scm' object should reference
//
def buildSilverAbleCExtension(extension_name) {
  internalBuildExtension(extension_name, false, true)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build with a C library, requiring silver-ableC.
// (see: ableC-nondeterministic-search)
//
// extension_name: the name of this extension, the 'scm' object should reference
//
def buildLibrarySilverAbleCExtension(extension_name) {
  internalBuildExtension(extension_name, true, true)
}

////////////////////////////////////////////////////////////////////////////////
//
// Do the above.
//
def internalBuildExtension(extension_name, hasLibrary, usesSilverAbleC) {

  melt.setProperties(silverBase: true, ablecBase: true, silverAblecBase: usesSilverAbleC)

  melt.trynode(extension_name) {

    def newenv // visible scope to all stages

    stage ("Build") {

      newenv = prepareWorkspace(extension_name, usesSilverAbleC)

      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          make(["clean", "build"])
        }
      }
    }

    if (hasLibrary) {
      stage ("Libraries") {
        withEnv(newenv) {
          dir("extensions/${extension_name}") {
            make(["libraries"])
          }
        }
      }
    }

    stage ("Examples") {
      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          make(["examples"])
        }
      }
    }

    stage ("Modular Analyses") {
      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          make(["analyses"])
        }
      }
    }

    stage ("Test") {
      withEnv(newenv) {
        dir("extensions/${extension_name}") {
          make(["test"])
        }
      }
    }

    /* If we've gotten all this way with a successful build, don't take up disk space */
    melt.clearGenerated()
  }
}

def make(targets, options="") {
  sh "make ${options} ${targets.join(' ')} -j -l 60"
}
