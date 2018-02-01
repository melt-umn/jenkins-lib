// Import ourself from the branch we're actually on:
library "github.com/melt-umn/jenkins-lib@${env.BRANCH_NAME}"

node {
  echo "Can we successfully access these constants"
  assert melt.ARTIFACTS == '/export/scratch/melt-jenkins/custom-stable-dump'
  assert melt.SILVER_WORKSPACE == '/export/scratch/melt-jenkins/custom-silver'
  
  echo "Done!"
}

