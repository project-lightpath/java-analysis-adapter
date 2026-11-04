/**
 * @name FLPATH-5000 direct reverse-call facts
 * @description Emits source-level call sites and their statically resolved callees.
 * @kind table
 * @id project-lightpath/flpath-5000/reverse-calls
 */

import java

from Call call, Callable caller, Callable callee
where
  caller = call.getCaller() and
  callee = call.getCallee() and
  caller.getCompilationUnit().fromSource()
select
  caller.getDeclaringType().getQualifiedName(),
  caller.getName(),
  callee.getDeclaringType().getQualifiedName(),
  callee.getName(),
  call.getLocation().getFile().getRelativePath(),
  call.getLocation().getStartLine(),
  caller.getQualifiedName(),
  callee.getQualifiedName()
