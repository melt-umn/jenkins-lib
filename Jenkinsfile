// Import ourself from the correct branch:
@Library("github.com/melt-umn/jenkins-lib${env.BRANCH_NAME}") _

node {
  echo "Begin test"
  echo "VAR: ${melt.CONSTANT}"
  melt.act()
}

