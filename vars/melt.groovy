// Define a few constants:
@groovy.transform.Field
CONSTANT='test'

// Define a few functions:
def act() {
  echo "Act called. Found ${env.BRANCH_NAME}"
  echo "Test #${currentBuild.getNumber()}"
}


