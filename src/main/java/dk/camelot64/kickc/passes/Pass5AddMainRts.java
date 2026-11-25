package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.asm.AsmChunk;
import dk.camelot64.kickc.asm.AsmComment;
import dk.camelot64.kickc.asm.AsmConstant;
import dk.camelot64.kickc.asm.AsmInstruction;
import dk.camelot64.kickc.asm.AsmLabelDecl;
import dk.camelot64.kickc.asm.AsmLine;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.SymbolRef;

import java.util.ListIterator;

/**
 * If the main method is called by JSR this adds an additional RTS
 */
public class Pass5AddMainRts extends Pass5AsmOptimization {

   public Pass5AddMainRts(Program program) {
      super(program);
   }

   public boolean optimize() {
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         String scopeLabel = chunk.getScopeLabel();
         if(scopeLabel.equals(ScopeRef.ROOT.getFullName())) {
            ListIterator<AsmLine> lineIterator = chunk.getLines().listIterator();
            while(lineIterator.hasNext()) {
               AsmLine line = lineIterator.next();
               if(line instanceof AsmInstruction instruction && instruction.getCpuOpcode() != null) {
                  if(instruction.getCpuOpcode().getMnemonic().equals("jsr")) {
                     if(SymbolRef.MAIN_PROC_NAME.equals(instruction.getOperand1())) {
                        // Look ahead past comments/constants/label decls for an existing RTS (CF-16)
                        int advanced = 0;
                        boolean hasRts = false;
                        while(lineIterator.hasNext()) {
                           AsmLine nextLine = lineIterator.next();
                           advanced++;
                           if(nextLine instanceof AsmComment || nextLine instanceof AsmConstant || nextLine instanceof AsmLabelDecl) {
                              continue;
                           }
                           if(nextLine instanceof AsmInstruction nextInstruction && nextInstruction.getCpuOpcode() != null
                                 && "rts".equals(nextInstruction.getCpuOpcode().getMnemonic())) {
                              hasRts = true;
                           }
                           break;
                        }
                        if(!hasRts) {
                           // Rewind right back to immediately after `jsr main` so RTS is never placed after `.segment Data`!
                           while(advanced-- > 0) {
                              lineIterator.previous();
                           }
                           addRts(lineIterator);
                           return true;
                        }
                     }
                  }
               }
            }
         }
      }
      return false;
   }

   private void addRts(ListIterator<AsmLine> lineIterator) {
      lineIterator.add(new AsmInstruction(getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      getLog().append("Adding RTS to root block ");
   }

}
