
import jenkins.model.Jenkins

// Location where we dump stable artifacts: jars, tarballs
@groovy.transform.Field
ARTIFACTS = '/export/scratch/melt-jenkins/custom-stable-dump'

// Location where we dump per-commit artifacts
@groovy.transform.Field
COMMIT_ARTIFACTS = '/export/scratch/melt-jenkins/commit-artifacts'

////////////////////////////////////////////////////////////////////////////////
//
// General notification of build failure.
// e.g. melt.notify(job: 'copper', ignoreBranches: true)
//
// job: Name required since env.JOB_NAME gives ugly names like 'melt-umn/copper/feature%2Fjenkins'
// ignoreBranches: Only notify for develop/master. (github always gets checkmarks though)
//
def notify(Map args) {
  args = [
    ignoreBranches: false
  ] + args
  assert args.job // required: name of the job
  
  if (args.ignoreBranches && env.BRANCH_NAME != 'develop' && env.BRANCH_NAME != 'master') {
    return
  }

  def status
  def color
  if (currentBuild.result == 'FAILURE') {
    status = 'failed'
    color = '#FF0000'
  } else {
    def previousResult = currentBuild.previousBuild?.result
    if (currentBuild.result == null && previousResult && previousResult == 'FAILURE') {
      status = 'back to normal'
      color = '#00FF00'
    } else {
      // No notification of continued success
      return
    }
  }

  def subject = "Build ${status}: '${args.job}' (${env.BRANCH_NAME}) [${env.BUILD_NUMBER}]"
  def body = """${env.BUILD_URL}"""
  emailext(
    subject: subject,
    body: body,
    to: 'evw@umn.edu', // Eric always wants to be notified.
    recipientProviders: [[$class: 'CulpritsRecipientProvider']]
  )
  //mimeType: 'text/html', // Stick to text for now
  
  slackSend(color: color, message: "${subject} ${body}")
}

////////////////////////////////////////////////////////////////////////////////
//
// Generic tool to help catch/rethrow errors, so we can notify.
// e.g. } catch(e) { melt.handle(e) } finally { melt.notify('silver') }
//
def handle(e) {
  // JENKINS-28822. Not sure if this works exactly as intended or not
  if(currentBuild.result == null) {
    echo "Setting failure flag"
    currentBuild.result = 'FAILURE'
  }

  throw e
}

////////////////////////////////////////////////////////////////////////////////
//
// Most jobs have a standard set of properties. So let's set them here in the stdlib.
// e.g. melt.setProperties(silverBase: true)
//
// For now, if there are custom properties for a special job, just don't use this.
//
def setProperties(Map args) {
  args = [
    silverBase: false,
    ablecBase: false,
    silverAblecBase: false,
    overrideJars: false
  ] + args
  def props = []
  def params = []
  
  // Keep metadata forever, but discard any stored artifacts (if any!) after awhile.
  // (Latest stable is always preserved.)
  props << buildDiscarder(logRotator(artifactDaysToKeepStr: '28', artifactNumToKeepStr: '3'))

  // We're obviously assuming there's only one machine Jenkins can use here.
  if (args.silverBase) {
    // Where to look to find Silver sources
    params << string(name: 'SILVER_BASE',
                     defaultValue: silver.getDefaultSilverBase(),
                     description: 'Silver installation path to use. "silver" is a special value that indicates to check out our own copy')
    // Also, where to look to find generated files from a successful build of those sources
    params << string(name: 'SILVER_GEN',
                     defaultValue: 'no',
                     description: 'Path to Silver generated files for SILVER_BASE. "no" means not available.')
  }
  
  if (args.ablecBase) {
    // Where to look to find AbleC sources
    params << string(name: 'ABLEC_BASE',
                     defaultValue: 'ableC',
                     description: 'Path to AbleC host checkout to use. "ableC" is a special value that indicates to check out our own copy')
    // Also, where to look to find generated files from a successful build of those sources
    params << string(name: 'ABLEC_GEN',
                     defaultValue: 'no',
                     description: 'Path to Silver generated files for ABLEC_BASE. "no" means not available.')
  }
  
  if (args.silverAblecBase) {
    // Where to look to find Silver-ableC sources
    params << string(name: 'SILVER_ABLEC_BASE',
                     defaultValue: 'silver-ableC',
                     description: 'Path to Silver-ableC host checkout to use. "silver-ableC" is a special value that indicates to check out and (if needed) build our own copy.')
  }

  if (args.overrideJars) {
    params << string(name: 'OVERRIDE_JARS',
                     defaultValue: 'no',
                     description: 'Path on coldpress to obtain jars from instead of using fetch-jars. "no" means find jars normally. "develop" means use the normal latest successful jars from the develop branch.')
    // TODO: Apparently boolean params are buggy?
    params << string(name: 'FETCH_COPPER_JARS',
                     defaultValue: 'no',
                     description: 'If "yes", use the latest Copper jars instead of the ones archived from the latest/last successful build.')
  }
  
  if (params) {
    props << parameters(params)
  }
  properties(props)
}

////////////////////////////////////////////////////////////////////////////////
//
// Build a job, but automatically inherit parameters given to this job.
// Also, allow providing parameters in an easier fashion, since all are strings.
//
// melt.buildJob('/foo', [ABLEC_BASE: 'bar'])
//
// Builds 'foo', with 'SILVER_BASE' inherited and 'ABLEC_BASE' set to a new value.
//
def buildJob(job, parameters=[:]) {
  def usingparameters = []
  def combinedparameters = params + parameters
  // At present it is not safe to iterate over maps inside jenkinsfiles
  // I don't have a bug to reference but 'jenkins serializable iterate over map'
  // returns appropriate results.
  // iterating over lists seems to be fine, so let's break this up by getting
  // a list of keys and using that
  for (key in combinedparameters.keySet()) {
    usingparameters << string(name: key, value: combinedparameters[key])
  }
  build(job: job, parameters: usingparameters)
}

////////////////////////////////////////////////////////////////////////////////
//
// Find out if a job exists
//
def doesJobExist(job) {
  // This is a completely ridiculous way to do this, but I can't see another way at the moment
  // due to the sandbox we're placed in here.

  def parts = job.split('/')
  def root = '/melt/jenkins/jenkins-home/jobs/'
  // 'jobname'
  if (parts.length == 1) {
    return fileExists(root + parts[0])
  }
  // '/jobname'
  if (parts.length == 2) {
    assert parts[0] == ''
    return fileExists(root + parts[1])
  }
  // '/path/repo/branch' (okay because in branch names / becomes %2F)
  if (parts.length == 4) {
    assert parts[0] == ''
    // potentially very fragile, because maybe they change this in the future, but oh well.
    // Try to deal better with shortened names: take the first and last 11 chars of the name,
    // concat fragments with a *
    // Take the first 11 characters
    def repoPattern = parts[2].take(11)
    // If the total length is longer than 22, append a * in the middle
    if (parts[2].length() > 22) {
      repoPattern += "*"
    }
    // Take the last 11 characters
    repoPattern += parts[2].drop(Math.max(11, parts[2].length() - 11))
    return 0 == sh(returnStatus: true, script: "(cd ${root}${parts[1]}/jobs/${repoPattern}/branches && grep '^${parts[3]}\$' */name-utf8.txt)")
  }

  error("melt.doesJobExist cannot understand '${job}'")
  return false
}

////////////////////////////////////////////////////////////////////////////////
//
// Find out if a branch exists
//
def doesBranchExist(branch, repo, url_base="https://github.com/melt-umn") {
  rc = sh(script: "git ls-remote --heads --exit-code ${url_base}/${repo}.git ${branch}", returnStatus: true)
  return rc == 0
}

////////////////////////////////////////////////////////////////////////////////
//
// Deletes generated files, and ensures the generated directory still exists.
//
def clearGenerated() {
    sh "rm -rf generated/* || true"
    sh "mkdir -p generated"
}

////////////////////////////////////////////////////////////////////////////////
//
// Wraps allocating a node with an exception handler that performs notifications.
// We generally have to have one node allocated for the whole job because nodes
// allocate a workspace, and we generally want our job to have one workspace.
//
def trynode(String jobname, Closure body) {
  node {
    try {
      body()
    }
    catch (e) {
      handle(e)
    }
    finally {
      notify(job: jobname)
    }
  }
}

////////////////////////////////////////////////////////////////////////////////
//
// Builds a downstream job, choosing same branch name if it exists.
// repo: e.g. "/melt-umn/ableC"
//
def buildProject(repo, parameters=[:]) {
  def jobname = "${repo}/${hudson.Util.rawEncode(env.BRANCH_NAME)}"
  // Check if it exists (but don't bother checking if we're already 'develop')
  if (env.BRANCH_NAME != 'develop' && !doesJobExist(jobname)) {
    // Fall back seamlessly
    jobname = "${repo}/develop"
  }
  buildJob(jobname, parameters)
}

////////////////////////////////////////////////////////////////////////////////
//
// Adds an annotation to the current build.
//
def annotate(String anno) {
  if (currentBuild.description == null) {
    currentBuild.description = anno
  } else {
    currentBuild.description += " ${anno}"
  }
}

////////////////////////////////////////////////////////////////////////////////
//
// Archives per-commit artifacts
//
def archiveCommitArtifacts(String artifacts) {
  def commitDir = "${COMMIT_ARTIFACTS}/\$(git rev-parse HEAD)"
  sh "mkdir -p ${commitDir}"
  sh "cp ${artifacts} ${commitDir}"
}

