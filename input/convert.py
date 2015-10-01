#!/usr/bin/python

import sys

if len(sys.argv) != 2:
    print 'Usage: input'
    exit

input_path = sys.argv[1]
gname = input_path.split('/')[-1].split('.')[0]
print 'graph %s {' % gname
with open(input_path, 'r') as f:
    for line in f:
        vs = line[:-1].split(' ')
        print '\t%s -- %s;' % (vs[0], vs[1])
print '}'
