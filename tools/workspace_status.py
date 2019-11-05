#!/usr/bin/env python

# This script will be run by bazel when the build process starts to
# generate key-value information that represents the status of the
# workspace. The output should be like
#
# KEY1 VALUE1
# KEY2 VALUE2
#
# If the script exits with non-zero code, it's considered as a failure
# and the output will be discarded.

from __future__ import print_function
import subprocess
import sys

CMD = ['git', 'describe', '--always', '--match', 'v[0-9].*', '--dirty']


def revision():
    try:
        return subprocess.check_output(CMD).strip().decode("utf-8")
    except OSError as err:
        print('could not invoke git: %s' % err, file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as err:
        print('error using git: %s' % err, file=sys.stderr)
        sys.exit(1)


print("STABLE_BUILD_OAUTH_LABEL %s" % revision())
