#!/usr/bin/env python3
# usage: bitbake-dumpsig FILE | whopulls.py VAR
# Walks "List of dependencies for variable X is [...]" lines backwards from VAR
# and prints every chain that leads to it, ending at the task body.
import ast, re, sys
target = sys.argv[1]
rdeps, task_body, taskdeps = {}, {}, ""
for line in sys.stdin:
    if line.startswith("Task dependencies: "):
        taskdeps = line
    m = re.match(r"List of dependencies for variable (\S+) is (\[.*\])$", line)
    if m:
        for dep in ast.literal_eval(m.group(2)):
            rdeps.setdefault(dep, []).append(m.group(1))
    m = re.match(r"Variable (do_\w+) value is (.*)", line)
    if m:
        task_body[m.group(1)] = m.group(2)

def walk(var, chain):
    users = rdeps.get(var, [])
    if not users:
        refs = [t for t, body in task_body.items() if "${%s}" % var in body]
        if not refs:
            refs = ["(exported to task env)" if "'%s[export]'" % var in taskdeps else "?"]
        print(" -> ".join(refs + list(reversed(chain))))
    for u in users:
        if u not in chain:
            walk(u, chain + [u])
walk(target, [target])
