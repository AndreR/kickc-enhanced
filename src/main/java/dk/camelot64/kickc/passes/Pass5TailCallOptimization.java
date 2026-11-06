package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.SymbolRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Tail Call Optimization (TCO):
 * Replaces 'JSR <target>' followed immediately by 'RTS' with 'JMP <target>' (and removes the now-unreachable RTS).
 *
 * Benefits:
 * - Saves 1 byte per occurrence (RTS removed).
 * - Saves 6 CPU cycles per call (eliminates 1 stack push & 1 stack pull).
 * - Reduces hardware stack depth by 2 bytes.
 */
public class Pass5TailCallOptimization extends Pass5AsmOptimization {

   public Pass5TailCallOptimization(Program program) {
      super(program);
   }

   private static class LineRef {
      final AsmChunk chunk;
      final int indexInChunk;
      final AsmLine line;
      final String scopeLabel;

      LineRef(AsmChunk chunk, int indexInChunk, AsmLine line, String scopeLabel) {
         this.chunk = chunk;
         this.indexInChunk = indexInChunk;
         this.line = line;
         this.scopeLabel = scopeLabel;
      }
   }

   public boolean optimize() {
      CpuOpcode jmpOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("jmp", CpuAddressingMode.ABS, false);
      if(jmpOpcode == null) return false;

      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         // Build sequential line list across all chunks
         List<LineRef> allLines = new ArrayList<>();
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            String scope = chunk.getScopeLabel();
            List<AsmLine> lines = chunk.getLines();
            for(int i = 0; i < lines.size(); i++) {
               allLines.add(new LineRef(chunk, i, lines.get(i), scope));
            }
         }

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(!(ref1.line instanceof AsmInstruction)) continue;

            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.isNoOptimize()) continue;
            if(inst1.getCpuOpcode() == null) continue;
            if(!inst1.getCpuOpcode().getMnemonic().equals("jsr")) continue;

            // Exclude root scope and calls to main
            if(ref1.scopeLabel != null && ref1.scopeLabel.equals(ScopeRef.ROOT.getFullName())) continue;
            if(inst1.getOperand1() != null && inst1.getOperand1().equals(SymbolRef.MAIN_PROC_NAME)) continue;

            // Find next executable instruction
            LineRef ref2 = null;
            boolean blocked = false;
            for(int j = i + 1; j < allLines.size(); j++) {
               LineRef candidate = allLines.get(j);
               if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate.line instanceof AsmLabel) {
                  // A label target exists between JSR and RTS: another branch might jump to RTS. Do not eliminate RTS!
                  blocked = true;
                  break;
               }
               if(candidate.line instanceof AsmScopeBegin || candidate.line instanceof AsmScopeEnd) {
                  blocked = true;
                  break;
               }
               if(candidate.line instanceof AsmInstruction) {
                  ref2 = candidate;
                  break;
               }
               // Any other construct (e.g. data, inline asm) blocks tail call
               blocked = true;
               break;
            }

            if(blocked || ref2 == null) continue;

            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.isNoOptimize() || inst2.getCpuOpcode() == null) continue;

            if(inst2.getCpuOpcode().getMnemonic().equals("rts")) {
               getLog().append("Tail Call Optimization: Replacing " + inst1.getAsm() + "; rts with jmp " + inst1.getOperand1());
               inst1.setCpuOpcode(jmpOpcode);
               ref2.chunk.getLines().remove(inst2);
               passModified = true;
               overallModified = true;
               break; // Restart scan after list modification
            }
         }
      }

      return overallModified;
   }
}
