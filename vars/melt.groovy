
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
    color = '#B00000'
  } else {
    def previousResult = currentBuild.previousBuild?.result
    if (currentBuild.result == null && previousResult && previousResult == 'FAILURE') {
      status = 'back to normal'
      color = '#00B000'
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
    ablecBase: false
  ] + args
  def props = []
  def params = []
  
  // Keep metadata forever, but discard any stored artifacts (if any!) after awhile.
  // (Latest stable is always preserved.)
  props << buildDiscarder(logRotator(artifactDaysToKeepStr: '90', artifactNumToKeepStr: '10'))

  if (args.silverBase) {
    // We're obviously assuming there's only one machine Jenkins can use here.
    params << string(name: 'SILVER_BASE',
                     defaultValue: '/export/scratch/melt-jenkins/custom-silver/',
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
// melt.buildJob('/foo/', [ABLEC_BASE: 'bar'])
//
// Builds 'foo', with 'SILVER_BASE' inherited and 'ABLEC_BASE' set to a new value.
//
def buildJob(job, parameters=[:]) {
  def using = []
  for (kv in (params + parameters)) {
    using << string(name: kv.key, value: kv.value)
  }
  build(job: job, parameters: using)
}

