#!/usr/bin/env python
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from __future__ import print_function
# TODO(davido): use Google style for importing instead:
# import optparse
# ...
# optparse.OptionParser
from optparse import OptionParser
from os import environ, path, makedirs
from subprocess import CalledProcessError, check_call, check_output
from xml.dom import minidom
import re
import sys

MAIN = '//tools/eclipse:classpath'
JRE = '/'.join([
  'org.eclipse.jdt.launching.JRE_CONTAINER',
  'org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType',
  'JavaSE-1.8',
])
# Map of targets to corresponding classpath collector rules
cp_targets = {
  MAIN: '//tools/eclipse:main_classpath_collect',
}

ROOT = path.abspath(__file__)
while not path.exists(path.join(ROOT, 'WORKSPACE')):
  ROOT = path.dirname(ROOT)

opts = OptionParser()
opts.add_option('--name', help='name of the generated project',
                action='store', default='gerrit-oauth-provider', dest='project_name')
args, _ = opts.parse_args()

def retrieve_ext_location():
  return check_output(['bazel', 'info', 'output_base']).strip()

def gen_primary_build_tool():
  bazel = check_output(['which', 'bazel']).strip()
  with open(path.join(ROOT, ".primary_build_tool"), 'w') as fd:
    fd.write("bazel=%s\n" % bazel)
    fd.write("PATH=%s\n" % environ["PATH"])

def _query_classpath(target):
  deps = []
  t = cp_targets[target]
  try:
    check_call(['bazel', 'build', t])
  except CalledProcessError:
    exit(1)
  name = 'bazel-bin/tools/eclipse/' + t.split(':')[1] + '.runtime_classpath'
  deps = [line.rstrip('\n') for line in open(name)]
  return deps

def gen_project(name='gerrit', root=ROOT):
  p = path.join(root, '.project')
  with open(p, 'w') as fd:
    print("""\
<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
  <name>%(name)s</name>
  <buildSpec>
    <buildCommand>
      <name>org.eclipse.jdt.core.javabuilder</name>
    </buildCommand>
  </buildSpec>
  <natures>
    <nature>org.eclipse.jdt.core.javanature</nature>
  </natures>
</projectDescription>\
    """ % {"name": name}, file=fd)

def gen_classpath(ext):
  def make_classpath():
    impl = minidom.getDOMImplementation()
    return impl.createDocument(None, 'classpath', None)

  def classpathentry(kind, path, src=None, out=None, exported=None):
    e = doc.createElement('classpathentry')
    e.setAttribute('kind', kind)
    # TODO(davido): Remove this and other exclude BUILD files hack
    # when this Bazel bug is fixed:
    # https://github.com/bazelbuild/bazel/issues/1083
    if kind == 'src':
      e.setAttribute('excluding', '**/BUILD')
    e.setAttribute('path', path)
    if src:
      e.setAttribute('sourcepath', src)
    if out:
      e.setAttribute('output', out)
    if exported:
      e.setAttribute('exported', 'true')
    doc.documentElement.appendChild(e)

  doc = make_classpath()
  src = set()
  lib = set()

  # Classpath entries are absolute for cross-cell support
  java_library = re.compile('bazel-out/local-fastbuild/bin/lib[^/]+[.]jar$')
  srcs = re.compile('(.*/external/[^/]+)/jar/(.*)[.]jar')
  for p in _query_classpath(MAIN):
    m = java_library.match(p)
    if m:
      src.add(".")
    else:
      if p.startswith("external"):
        p = path.join(ext, p)
      lib.add(p)

  for s in sorted(src):
    out = None

    if s.startswith('lib/'):
      out = 'eclipse-out/lib'

    p = path.join(s, 'java')
    if path.exists(p):
      classpathentry('src', p, out=out)
      continue

    for env in ['main', 'test']:
      o = None
      if out:
        o = out + '/' + env
      elif env == 'test':
        o = 'eclipse-out/test'

      for srctype in ['java', 'resources']:
        p = path.join(s, 'src', env, srctype)
        if path.exists(p):
          classpathentry('src', p, out=o)

  for libs in [lib]:
    for j in sorted(libs):
      s = None
      m = srcs.match(j)
      if m:
        prefix = m.group(1)
        suffix = m.group(2)
        p = path.join(prefix, "src", "%s-src.jar" % suffix)
        if path.exists(p):
          s = p
      classpathentry('lib', j, s)

  classpathentry('con', JRE)
  classpathentry('output', 'eclipse-out/classes')

  p = path.join(ROOT, '.classpath')
  with open(p, 'w') as fd:
    doc.writexml(fd, addindent='\t', newl='\n', encoding='UTF-8')

try:
  ext_location = retrieve_ext_location()
  gen_project(args.project_name)
  gen_classpath(ext_location)
  gen_primary_build_tool()

  try:
    check_call(['bazel', 'build', MAIN])
  except CalledProcessError:
    exit(1)
except KeyboardInterrupt:
  print('Interrupted by user', file=sys.stderr)
  exit(1)
