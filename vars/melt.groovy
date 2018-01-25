
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
// e.g. melt.notify('copper', ignoreBranches: true)
//
// job: Name required since env.JOB_NAME gives ugly names like 'melt-umn/copper/feature%2Fjenkins'
// ignoreBranches: Only notify for develop/master. (github always gets checkmarks though)
//
def notify(job, ignoreBranches=false) {
  if (currentBuild.result != 'FAILURE') {
    return
  }
  if (ignoreBranches && env.BRANCH_NAME != 'develop' && env.BRANCH_NAME != 'master') {
    return
  }

  def subject = "Build failed: '${job}' (${env.BRANCH_NAME}) [${env.BUILD_NUMBER}]"
  def body = """${env.BUILD_URL}"""
  emailext(
    subject: subject,
    body: body,
    recipientProviders: [[$class: 'CulpritsRecipientProvider']]
  )
  //mimeType: 'text/html', // Stick to text for now
  //to: 'evw@umn.edu', // Do we want to spam Eric?
  
  // I'm not on slack, comment from someone who is?
  //slackSend(color: '#B00000', message: "${subject} ${body}")
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
def setProperties(silverBase=false, ablecBase=false) {
  def props = []
  def params = []
  
  // Keep metadata forever, but discard any stored artifacts (if any!) after awhile.
  // (Latest stable is always preserved.)
  props << buildDiscarder(logRotator(artifactDaysToKeepStr: '90', artifactNumToKeepStr: '10'))

  if (silverBase) {
    // We're obviously assuming there's only one machine Jenkins can use here.
    params << string(name: 'SILVER_BASE',
                     defaultValue: '/export/scratch/melt-jenkins/custom-silver/',
                     description: 'Silver installation path to use.')
  }
  
  if (ablecBase) {
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
// melt.buildJob(job: '/foo/', parameters: [ABLEC_BASE: 'bar'])
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

////////////////////////////////////////////////////////////////////////////////
//
// Find out if a job exists
//
def doesJobExist(job) {
  echo "${jenkins.model.Jenkins.instance.getJobNames}"
  return false
}


