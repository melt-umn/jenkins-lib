
import jenkins.model.Jenkins

// Location where we dump stable artifacts: jars, tarballs
@groovy.transform.Field
ARTIFACTS = '/export/scratch/melt-jenkins/custom-stable-dump'

// Location of a Silver checkout (w/ jars)
@groovy.transform.Field
SILVER_WORKSPACE = '/export/scratch/melt-jenkins/custom-silver'

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
    overrideJars: false
  ] + args
  def props = []
  def params = []
  
  // Keep metadata forever, but discard any stored artifacts (if any!) after awhile.
  // (Latest stable is always preserved.)
  props << buildDiscarder(logRotator(artifactDaysToKeepStr: '28', artifactNumToKeepStr: '3'))

  if (args.silverBase) {
    // We're obviously assuming there's only one machine Jenkins can use here.
    params << string(name: 'SILVER_BASE',
                     defaultValue: SILVER_WORKSPACE,
                     description: 'Silver installation path to use.')
  }
  
  if (args.ablecBase) {
    // Where to look to find AbleC sources
    params << string(name: 'ABLEC_BASE',
                     defaultValue: 'ableC',
                     description: 'Path to AbleC host checkout to use. "ableC" is a special value that indicates to check out our own copy of develop')
    // Also, where to look to find generated files from a successful build of those sources
    params << string(name: 'ABLEC_GEN',
                     defaultValue: 'no',
                     description: 'Path to Silver generated files for ABLEC_BASE. "no" means not available.')
  }

  if (args.overrideJars) {
    params << string(name: 'OVERRIDE_JARS',
                     defaultValue: 'no',
                     description: 'Path on coldpress to obtain jars from instead of using fetch-jars.')
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
  def root = '/var/lib/jenkins/jobs/'
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
    // potentially very fragile, because maybe they change this in the future, but oh well
    return 0 == sh(returnStatus: true, script: "(cd ${root}${parts[1]}/jobs/${parts[2]}/branches && grep '^${parts[3]}\$' */name-utf8.txt)")
  }

  error("melt.doesJobExist cannot understand '${job}'")
  return false
}

////////////////////////////////////////////////////////////////////////////////
//
// Compute the typical additions to the environment for building silver
// projects from jenkins, using SILVER_BASE parameter
//
def getSilverEnv() {
  // Notify when we're not using the normal silver build.
  if (params.SILVER_BASE != SILVER_WORKSPACE) {
    echo "\n\nCUSTOM SILVER IN USE.\nUsing: ${params.SILVER_BASE}\n\n"
  }
  // We generate files in the workspace ./generated, essentially always
  def GEN = "${pwd()}/generated"
  // Neat Jenkins trick to add things to PATH:
  return [
    "PATH+silver=${params.SILVER_BASE}/support/bin/",
    "PATH+nailgun=:${params.SILVER_BASE}/support/nailgun/",
    "SILVER_GEN=${GEN}"
  ]
  // Currently not setting SVFLAGS by default, but we could in the future
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
  echo "in body with ${jobname}"
  node {
    echo "in node"
    try {
      echo "in try"
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

