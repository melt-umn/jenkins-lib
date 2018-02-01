
This repository hosts shared code for use in `Jenkinsfile`s of our projects here on github.

Code in this repository is public domain. We do not claim copyright. Where legally required, you may opt for MIT license.

## How this works

In a `Jenkinsfile`, write:

```
// Get the 'melt' variable in scope:
library "github.com/melt-umn/jenkins-lib"
```

From there, you may call/access the functions/variables you find in `vars/melt.groovy`. The above imports a `melt` object, and so variables and methods you see in that file will be on that object. For example:

```
melt.notify(job: 'silver')
```

This will call the `notify` method in `vars/melt.groovy`.

## How to make changes to this code

1. Branch, make changes.
2. Test them out with this repository's own `Jenkinsfile`. Jenkins has a "Replay" feature that can help you figure out what syntax tweak will work.
3. After fixing all your bugs, rewrite the history of your branch to be **clean** and comprehensible. (Try `git rebase -i master` and using `fixup` on everything but your first commit. When you force push, make sure it's only your branch, always spell it out to be safe: `git push -f origin MY_BRANCH`)
4. You can test a script in another repo by making it use a particular branch: (`library 'github.com/melt-umn/jenkins-lib@MY_BRANCH'`). Do this in a branch, and then just delete it later.
5. Pull request / merge.
6. Sigh, test to make sure you didn't break anything. Probably by triggering a rebuild of Silver in Jenkins.
7. Delete branch.

A bit annoying, but better than duplicating code all over, eh?


