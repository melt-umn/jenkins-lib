
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
// Checkout an extension into the local workspace (into extensions/)
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def checkoutExtension(ext, url_base="https://github.com/melt-umn") {
  /* The *right* way of doing this, I think.  Unfourtunately this seems to be bugged?
  checkout resolveScm(
    source: git(url: "${url_base}/${ext}.git"),
    targets: [env.BRANCH_NAME, 'develop'],
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.EXTS_BASE}/${ext}"],
                 [$class: 'CleanCheckout']])
   */
  lock ("checkout-${ext}") {
    if (params.EXTS_BASE != 'extensions' && fileExists("${params.EXTS_BASE}/${ext}")) {
      echo "Extension ${ext} already checked out"
    } else {
      branch = melt.doesBranchExist(env.BRANCH_NAME, ext, url_base)? env.BRANCH_NAME : "develop"
      echo "Checking out our own copy of extension ${ext} (branch ${branch})"
      
      checkout([
          $class: 'GitSCM',
          branches: [[name: "*/${branch}"]],
          doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.EXTS_BASE}/${ext}"],
                      [$class: 'CleanCheckout']],
          submoduleCfg: [],
          userRemoteConfigs: [[url: "${url_base}/${ext}.git"]]])
    }
  }
}

////////////////////////////////////////////////////////////////////////////////
//
// Get set up to build. Creates:
//
// ./generated/              (empty)
// ./ableC/                  (if no ABLEC_BASE parameter is set)
// ./extensions/name         (where this extension is checked out)
// ./extensions/more         (if given)
//
def prepareWorkspace(name) {
  
  // Clean Silver-generated files from previous builds in this workspace
  melt.clearGenerated()
  
  // Get this extension
  // TODO: doesn't currently handle extensions outside the melt-umn org
  checkoutExtension(name)

  // Get the dependencies of this extension from its Makefile
  def extensions = []
  dir ("${params.EXTS_BASE}/${name}") {
    extensions = sh(returnStdout: true, script: "grep 'EXT_DEPS *=.*' Makefile | sed 's/EXT_DEPS *=//'").tokenize()
  }
  echo "Dependencies: ${extensions}"

  // Get the other extensions, preferring same branch name over develop
  for (ext in extensions) {
    checkoutExtension(ext)
  }

  // Get Silver
  def silver_base = silver.resolveSilver()

  // Get AbleC
  def ablec_base = resolveAbleC()

  // Resolve extensions path
  def exts_base = params.EXTS_BASE.startsWith('/')? params.EXTS_BASE : "${env.WORKSPACE}/${params.EXTS_BASE}"

  def newenv = silver.getSilverEnv(silver_base) + [
    "ABLEC_BASE=${ablec_base}",
    "EXTS_BASE=${exts_base}",
    // cilk headers:
    "C_INCLUDE_PATH=/export/scratch/thirdparty/opencilk-2.0.1/lib/clang/14.0.6/include/cilk/include",
    "LIBRARY_PATH=/export/scratch/thirdparty/opencilk-2.0.1/lib/clang/14.0.6/lib/x86_64-unknown-linux-gnu",
  ]
  
  return newenv
}

////////////////////////////////////////////////////////////////////////////////
//
// A normal AbleC extension build. (see: ableC-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
//
def buildNormalExtension(extension_name) {
  internalBuildExtension(extension_name, false)
}

////////////////////////////////////////////////////////////////////////////////
//
// An AbleC extension build with a C library. (see: ableC-lib-skeleton)
//
// extension_name: the name of this extension, the 'scm' object should reference
//
def buildLibraryExtension(extension_name) {
  internalBuildExtension(extension_name, true)
}

////////////////////////////////////////////////////////////////////////////////
//
// Do the above.
//
def internalBuildExtension(extension_name, hasLibrary) {

  melt.setProperties(silverBase: true, ablecBase: true)

  melt.trynode(extension_name) {

    def newenv // visible scope to all stages

    stage ("Build") {

      newenv = prepareWorkspace(extension_name)

      withEnv(newenv) {
        dir("${params.EXTS_BASE}/${extension_name}") {
          if (params.EXTS_BASE == 'extensions') {
            make(["deprealclean"])
          }
          make(["build"])
        }
      }
    }

    if (hasLibrary) {
      stage ("Libraries") {
        withEnv(newenv) {
          dir("${params.EXTS_BASE}/${extension_name}") {
            make(["libraries"])
          }
        }
      }
    }

    stage ("Examples") {
      withEnv(newenv) {
        dir("${params.EXTS_BASE}/${extension_name}") {
          make(["examples"])
        }
      }
    }

    stage ("Modular Analyses") {
      withEnv(newenv) {
        dir("${params.EXTS_BASE}/${extension_name}") {
          make(["analyses"])
        }
      }
    }

    stage ("Test") {
      withEnv(newenv) {
        dir("${params.EXTS_BASE}/${extension_name}") {
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
