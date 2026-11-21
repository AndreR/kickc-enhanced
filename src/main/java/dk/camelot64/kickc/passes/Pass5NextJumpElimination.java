package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.ArrayList;
import java.util.List;

/**
 * Optimize assembler code by removing jumps to labels immediately following the jump
 */
public class Pass5NextJumpElimination extends Pass5AsmOptimization {

   public Pass5NextJumpElimination(Program program) {
      super(program);
   }

   public boolean optimize() {
      List<AsmLine> removeLines = new ArrayList<>();
      AsmInstruction candidate = null;
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(candidate != null && line instanceof AsmLabel) {
               if(((AsmLabel) line).getLabel().equals(candidate.getOperandJumpTarget())) {
                  removeLines.add(candidate);
               }
            }
            if(line instanceof AsmInstruction instruction) {
               candidate = null;
               if(instruction.getCpuOpcode().isJump() && !instruction.getCpuOpcode().getMnemonic().equals("jsr")) {
                  candidate = instruction;
               }
            } else if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl || line instanceof AsmLabel) {
               // CF-05: Allowlist of zero-byte directives that cannot break fall-through to the target label
            } else {
               // Any other directive (.align, AsmLoopAlign, .segment, .pc, data, inline kickasm, scope) breaks fall-through
               candidate = null;
            }
         }

      }
      remove(removeLines);
      return removeLines.size() > 0;
   }
}
