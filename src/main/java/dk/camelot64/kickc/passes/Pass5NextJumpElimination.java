package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optimize assembler code by removing jumps to labels immediately following the jump
 */
public class Pass5NextJumpElimination extends Pass5AsmOptimization {

   public Pass5NextJumpElimination(Program program) {
      super(program);
   }

   public boolean optimize() {
      List<AsmLine> removeLines = new ArrayList<>();
      Set<String> modifiedScopes = new LinkedHashSet<>();
      AsmInstruction candidate = null;
      String candidateScope = "";
      String currentScope = "";
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin sb) {
               currentScope = sb.getLabel() != null ? sb.getLabel() : "";
               candidate = null;
               continue;
            } else if(line instanceof AsmScopeEnd) {
               currentScope = "";
               candidate = null;
               continue;
            }
            if(!isScopeDirty(currentScope)) {
               candidate = null;
               continue;
            }
            if(candidate != null && line instanceof AsmLabel) {
               if(((AsmLabel) line).getLabel().equals(candidate.getOperandJumpTarget())) {
                  removeLines.add(candidate);
                  modifiedScopes.add(candidateScope);
               }
            }
            if(line instanceof AsmInstruction instruction) {
               candidate = null;
               if(instruction.getCpuOpcode().isJump() && !instruction.getCpuOpcode().getMnemonic().equals("jsr")) {
                  candidate = instruction;
                  candidateScope = currentScope;
               }
            } else if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl || line instanceof AsmLabel) {
               // CF-05: Allowlist of zero-byte directives that cannot break fall-through to the target label
            } else {
               // Any other directive (.align, AsmLoopAlign, .segment, .pc, data, inline kickasm, scope) breaks fall-through
               candidate = null;
            }
         }

      }
      if(!removeLines.isEmpty()) {
         remove(removeLines);
         for(String s : modifiedScopes) {
            markScopeDirty(s);
         }
         invalidateRoundStructure();
         return true;
      }
      return false;
   }
}
