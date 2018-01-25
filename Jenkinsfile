// Import ourself from the branch we're actually on:
library "github.com/melt-umn/jenkins-lib@${env.BRANCH_NAME}"

node {
  echo "Begin test"
  melt.act()
  echo "VAR: ${melt.CONSTANT}"
}

