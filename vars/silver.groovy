
import jenkins.model.Jenkins

// Location of a Silver checkout (w/ jars)
@groovy.transform.Field
SILVER_WORKSPACE = '/export/scratch/melt-jenkins/custom-silver'

////////////////////////////////////////////////////////////////////////////////
//
// Compute the typical additions to the environment for building silver
// projects from jenkins, using SILVER_BASE parameter
//
def getSilverEnv() {

  def SILVER_BASE = params.SILVER_BASE
  if (params.SILVER_BASE == 'silver') {
    echo "Checking out our own copy of silver"

    checkout([$class: 'GitSCM',
              branches: [[name: "*/${env.BRANCH_NAME}"], [name: '*/develop']],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'silver'],
                           [$class: 'CleanCheckout']],
              submoduleCfg: [],
              userRemoteConfigs: [[url: 'https://github.com/melt-umn/silver.git']]])

    // TODO: we *might* wish to melt.annotate if we're checking out a *branch* of Silver, figure out how to check? and maybe consider whether we want that?

    SILVER_BASE = "${env.WORKSPACE}/silver/"

    // Try to obtain jars from previous builds.
    dir(SILVER_BASE) {
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
  }
  
  // Notify when we're not using the normal silver build.
  if (SILVER_BASE != SILVER_WORKSPACE) {
    echo "\n\nCUSTOM SILVER IN USE.\nUsing: ${params.SILVER_BASE}\n\n"
    melt.annotate("Custom Silver.")
  }
  // We generate files in the workspace ./generated, essentially always
  def GEN = "${env.WORKSPACE}/generated"
  // Neat Jenkins trick to add things to PATH:
  return [
    "PATH+silver=${SILVER_BASE}/support/bin/",
    "PATH+nailgun=:${SILVER_BASE}/support/nailgun/",
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
  if (env.BRANCH_NAME != 'master' &&
      env.BRANCH_NAME != 'develop' &&
      doesJobExist("/melt-umn/silver/${hudson.Util.rawEncode(env.BRANCH_NAME)}")) {
    // We need to check out a fresh copy of silver
    return 'silver'
  } else {
    // We can just use custom-silver
    return SILVER_WORKSPACE
  }
}
