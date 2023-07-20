// Import ourself from the branch we're actually on:
library "github.com/melt-umn/jenkins-lib@${env.BRANCH_NAME}"

node {
  echo "Can we successfully access these constants"
  assert melt.ARTIFACTS == '/export/scratch/melt-jenkins/custom-stable-dump'
  assert silver.SILVER_WORKSPACE == '/export/scratch/melt-jenkins/custom-silver'
  
//  // The latter variable wins in this test:
//  withEnv(["VAR_ASDF=first", "VAR_ASDF=overridden"]) {
//    // single quotes, so bash is expanding this variable:
//    sh 'echo ${VAR_ASDF}'
//  }

  echo "Test job existence function"
  assert !melt.doesJobExist('asdfasdf')
  assert !melt.doesJobExist('/asdfasdf')
  assert !melt.doesJobExist('/melt-umn/silver/no_such_branch_exists')
  // We have no plain jobs anymore:
  //assert melt.doesJobExist('x-metaII-artifacts')
  //assert melt.doesJobExist('/x-metaII-artifacts')
  assert melt.doesJobExist('/melt-umn/silver/develop')
  echo "Done!"

  // As a reference, I find this helpful to refer to
  sh "printenv"
  
  melt.annotate("Test.")
  melt.annotate("Annotation.")
  
//  // Both variables were in agreement with this test:
//  node {
//    echo "${env.WORKSPACE}"
//    sh 'echo ${WORKSPACE}'
//    node {
//      echo "${env.WORKSPACE}"
//      sh 'echo ${WORKSPACE}'
//    }
//  }

  echo "isExecutorAvailable: " + melt.isExecutorAvailable()

  waitUntil { melt.isExecutorAvailable() }

  echo "executor available"

}

