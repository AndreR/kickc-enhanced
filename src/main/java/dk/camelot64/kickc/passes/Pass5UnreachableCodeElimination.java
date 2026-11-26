package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.asm.AsmChunk;
import dk.camelot64.kickc.asm.AsmComment;
import dk.camelot64.kickc.asm.AsmInstruction;
import dk.camelot64.kickc.asm.AsmLine;
import dk.camelot64.kickc.asm.AsmScopeBegin;
import dk.camelot64.kickc.asm.AsmScopeEnd;
import dk.camelot64.kickc.model.Program;

import java.util.ListIterator;

/**
 * Optimize assembler code by removing anything following an RTS or JMP (meaning they will never execute.) - until the next label/scope occurs.
 * Such JMP's can occur when double JMP's are eliminated
 */
public class Pass5UnreachableCodeElimination extends Pass5AsmOptimization {

   public Pass5UnreachableCodeElimination(Program program) {
      super(program);
   }

   public boolean optimize() {
      boolean optimized = false;
      String currentScope = "";

      // Find RTS/JMP followed by code
      boolean afterExit = false;
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         ListIterator<AsmLine> lineIt = chunk.getLines().listIterator();
         while(lineIt.hasNext()) {
            AsmLine line = lineIt.next();
            if(line instanceof AsmScopeBegin sb) {
               currentScope = sb.getLabel() != null ? sb.getLabel() : "";
               afterExit = false;
               continue;
            } else if(line instanceof AsmScopeEnd) {
               currentScope = "";
               afterExit = false;
               continue;
            }
            if(!isScopeDirty(currentScope)) {
               afterExit = false;
               continue;
            }
            if(line instanceof AsmInstruction) {
               if(afterExit) {
                  getLog().append("Removing unreachable instruction " + line.toString());
                  lineIt.remove();
                  markScopeDirty(currentScope);
                  optimized = true;
               } else {
                  AsmInstruction asmInstruction = (AsmInstruction) line;
                  if(asmInstruction.getCpuOpcode().getMnemonic().equals("rts") || asmInstruction.getCpuOpcode().getMnemonic().equals("jmp")) {
                     afterExit = true;
                  }
               }
            } else if(line instanceof AsmComment) {
               // ignore comments
            } else {
               afterExit = false;
            }
         }
      }

      if(optimized) {
         invalidateRoundStructure();
      }
      return optimized;
   }
}
