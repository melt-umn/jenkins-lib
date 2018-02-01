// Import ourself from the branch we're actually on:
library "github.com/melt-umn/jenkins-lib@${env.BRANCH_NAME}"

node {
  // Can we successfully access these constants:
  assert melt.ARTIFACTS == '/export/scratch/melt-jenkins/custom-stable-dump'
  assert melt.SILVER_WORKSPACE == '/export/scratch/melt-jenkins/custom-silver'
  
  // Test job existence function
  assert !melt.doesJobExist('asdfasdf')
  assert !melt.doesJobExist('/asdfasdf')
  assert !melt.doesJobExist('/melt-umn/silver/no_such_branch_exists')
  assert doesJobExist('x-metaII-artifacts')
  assert doesJobExist('/x-metaII-artifacts')
  assert doesJobExist('/melt-umn/silver/develop')
}

