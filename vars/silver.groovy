
import jenkins.model.Jenkins

// Location of a Silver checkout (w/ jars)
@groovy.transform.Field
SILVER_WORKSPACE = '/export/scratch/melt-jenkins/custom-silver'

////////////////////////////////////////////////////////////////////////////////
//
// Obtain a path to Silver to use to build this project
//
// e.g. def silver_base = silver.resolveSilver()
//
// NOTE: prioritizes BRANCH_NAME over 'develop'
//
def resolveSilver() {
  
  if (params.SILVER_BASE == 'silver') {
    echo "Checking out our own copy of Silver"

    checkout([$class: 'GitSCM',
              branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'silver'],
                           [$class: 'CleanCheckout']],
              submoduleCfg: [],
              userRemoteConfigs: [[url: 'https://github.com/melt-umn/silver.git']]])
    
    melt.annotate("Checkout Silver.")

    // Try to obtain jars from previous builds.
    dir("${env.WORKSPACE}/silver") {
      String branchJob = "/melt-umn/silver/${hudson.Util.rawEncode(env.BRANCH_NAME)}"
      try {
        // If the last build has artifacts, use those.
        copyArtifacts(projectName: branchJob, selector: lastCompleted())
        melt.annotate("Jars from branch (prev).")
      } catch (hudson.AbortException exc2) {
        try {
          // If there is a last successful build, use those.
          copyArtifacts(projectName: branchJob, selector: lastSuccessful())
          melt.annotate("Jars from branch (successful).")
        } catch (hudson.AbortException exc3) {
          // Fall back to using fetch-jars
          sh "./fetch-jars"
        }
      }
    }

    return "${env.WORKSPACE}/silver"
  }
  
  // Notify when we're not using the normal silver build.
  if (params.SILVER_BASE != SILVER_WORKSPACE) {
    echo "\n\nCUSTOM SILVER IN USE.\nUsing: ${params.SILVER_BASE}\n\n"
    melt.annotate("Custom Silver.")
  }
  return params.SILVER_BASE
}

////////////////////////////////////////////////////////////////////////////////
//
// Compute the typical additions to the environment for building silver
// projects from jenkins, using silver_base parameter
//
def getSilverEnv(silver_base=resolveSilver()) {
  // We generate files in the workspace ./generated, essentially always
  def GEN = "${env.WORKSPACE}/generated"
  // Neat Jenkins trick to add things to PATH:
  return [
    "PATH+silver=${silver_base}/support/bin/",
    "PATH+nailgun=:${silver_base}/support/nailgun/",
    "SILVER_GEN=${GEN}"
  ]
  // Currently not setting SVFLAGS by default, but we could in the future
}

////////////////////////////////////////////////////////////////////////////////
//
// Compute the default value of the SILVER_BASE parameter to use, by determining
// if we can use custom-silver.
//
def getDefaultSilverBase() {
  if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME == 'develop') {
    // We can just use custom-silver
    return SILVER_WORKSPACE
  }
  
  def silverBranchExists = false
  node {
    // Need to be running inside a node in order to check this
    silverBranchExists = melt.doesJobExist("/melt-umn/silver/${hudson.Util.rawEncode(env.BRANCH_NAME)}")
  }
  if (silverBranchExists) {
    // We need to check out a fresh copy of silver
    return 'silver'
  } else {
    // We can just use custom-silver
    return SILVER_WORKSPACE
  }
}
