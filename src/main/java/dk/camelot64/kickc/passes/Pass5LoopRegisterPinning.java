package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Assembler Peephole Optimization: Loop Register Pinning.
 *
 * Detects counting / offset loops where an index variable in Zero-Page is modified
 * by AXS/DEX at the loop step and used for array indexing at the loop head via LDY / LDA arr,y.
 *
 * Transforms:
 * Pre-loop:
 *    STA var
 * -> STA var; TAX
 *
 * Loop head:
 *    LDY var
 *    LDA arr,y
 * -> LDA arr,x
 *
 * Loop step:
 *    LAX var
 *    AXS #imm
 * -> TXA
 *    AXS #imm
 *
 * Saves 4-7 CPU cycles per loop iteration by keeping the loop index pinned in CPU register X.
 */
public class Pass5LoopRegisterPinning extends Pass5AsmOptimization {

   private static class LineRef {
      final AsmChunk chunk;
      final AsmLine line;
      LineRef(AsmChunk chunk, AsmLine line) {
         this.chunk = chunk;
         this.line = line;
      }
   }

   public Pass5LoopRegisterPinning(Program program) {
      super(program);
   }

   private static String cleanVar(String op) {
      if(op == null) return null;
      String v = op.trim();
      if(v.startsWith(".z ")) v = v.substring(3).trim();
      int comma = v.indexOf(',');
      if(comma != -1) v = v.substring(0, comma).trim();
      return v;
   }

   private static boolean isLabelMatch(String l1, String l2) {
      if(l1 == null || l2 == null) return false;
      l1 = l1.trim();
      l2 = l2.trim();
      if(l1.equals(l2)) return true;
      if(l1.endsWith("." + l2) || l2.endsWith("." + l1)) return true;
      return false;
   }

   private static boolean clobbersX(AsmInstruction inst) {
      CpuOpcode op = inst.getCpuOpcode();
      if(op == null) return true;
      String mnem = op.getMnemonic();
      if(mnem.equals("ldx") || mnem.equals("tax") || mnem.equals("tsx") || mnem.equals("lax") ||
         mnem.equals("inx") || mnem.equals("dex") || mnem.equals("axs") || mnem.equals("plx") ||
         mnem.equals("jsr")) {
         return true;
      }
      return false;
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;

      CpuOpcode taxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false);
      CpuOpcode txaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("txa", CpuAddressingMode.NON, false);
      CpuOpcode ldaXOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.ABX, false);

      // 1. Build scoped alias resolver for all .label declarations across all chunks
      Map<String, String> scopedAliasMap = new HashMap<>();
      String curChunkScope = null;
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               curChunkScope = ((AsmScopeBegin) line).getLabel();
            } else if(line instanceof AsmScopeEnd) {
               curChunkScope = null;
            } else if(line instanceof AsmLabelDecl) {
               AsmLabelDecl decl = (AsmLabelDecl) line;
               String name = decl.getName().trim();
               String val = decl.getValue().trim();
               if(val.startsWith(".z ")) val = val.substring(3).trim();
               String baseName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
               String scopeName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : curChunkScope;
               if(scopeName != null && !scopeName.isEmpty()) {
                  scopedAliasMap.put(scopeName + "." + baseName, val);
               } else {
                  scopedAliasMap.put(baseName, val);
               }
            }
         }
      }

      java.util.function.BiFunction<String, String, String> resolve = (name, scope) -> {
         if(name == null) return null;
         String cur = cleanVar(name);
         for(int step = 0; step < 5; step++) {
            if(scope != null && !scope.isEmpty() && scopedAliasMap.containsKey(scope + "." + cur)) {
               cur = scopedAliasMap.get(scope + "." + cur);
            } else if(scopedAliasMap.containsKey(cur)) {
               cur = scopedAliasMap.get(cur);
            } else {
               break;
            }
         }
         return cur;
      };

      // 2. Flatten all lines across chunks
      List<LineRef> allLines = new ArrayList<>();
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            allLines.add(new LineRef(chunk, line));
         }
      }

      // 3. Scan for loop head: L_head followed by LDY var; LDA arr,y
      String currentScope = null;
      for(int i = 0; i < allLines.size(); i++) {
         LineRef headRef = allLines.get(i);
         if(headRef.line instanceof AsmScopeBegin) {
            currentScope = ((AsmScopeBegin) headRef.line).getLabel();
         } else if(headRef.line instanceof AsmScopeEnd) {
            currentScope = null;
         }
         if(!(headRef.line instanceof AsmLabel)) continue;
         AsmLabel headLabel = (AsmLabel) headRef.line;
         String headName = headLabel.getLabel();

         // Find instruction 1 (ldy var)
         int ldyIdx = -1;
         for(int j = i + 1; j < allLines.size(); j++) {
            AsmLine l = allLines.get(j).line;
            if(l instanceof AsmComment || l instanceof AsmLabelDecl || l instanceof AsmConstant) continue;
            if(l instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) l;
               if(inst.getCpuOpcode() != null && "ldy".equals(inst.getCpuOpcode().getMnemonic())) {
                  ldyIdx = j;
               }
            }
            break;
         }
         if(ldyIdx == -1) continue;
         AsmInstruction ldyInst = (AsmInstruction) allLines.get(ldyIdx).line;
         String var1 = ldyInst.getOperand1();
         String canonVar1 = resolve.apply(var1, currentScope);
         if(canonVar1 == null) continue;

         // Find instruction 2 (lda arr,y)
         int ldaIdx = -1;
         for(int j = ldyIdx + 1; j < allLines.size(); j++) {
            AsmLine l = allLines.get(j).line;
            if(l instanceof AsmComment || l instanceof AsmLabelDecl || l instanceof AsmConstant) continue;
            if(l instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) l;
               if(inst.getCpuOpcode() != null && "lda".equals(inst.getCpuOpcode().getMnemonic()) &&
                  (CpuAddressingMode.ABY.equals(inst.getCpuOpcode().getAddressingMode()) ||
                   CpuAddressingMode.IZY.equals(inst.getCpuOpcode().getAddressingMode()))) {
                  ldaIdx = j;
               }
            }
            break;
         }
         if(ldaIdx == -1) continue;
         AsmInstruction ldaInst = (AsmInstruction) allLines.get(ldaIdx).line;

         // 4. Find step block that branches back to headName
         int branchIdx = -1;
         int axsIdx = -1;
         int laxIdx = -1;
         for(int j = ldaIdx + 1; j < allLines.size(); j++) {
            AsmLine l = allLines.get(j).line;
            if(l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) break;
            if(l instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) l;
               CpuOpcode op = inst.getCpuOpcode();
               if(op == null) continue;
               String mnem = op.getMnemonic();
               String target = inst.getOperandJumpTarget();
               if(target == null) target = inst.getOperand1();
               if((mnem.startsWith("b") || mnem.equals("jmp")) && isLabelMatch(headName, target)) {
                  // Found branch to head! Now look backwards for axs and lax
                  branchIdx = j;
                  boolean xSafe = true;
                  int curAxs = -1;
                  int curLax = -1;
                  for(int k = branchIdx - 1; k >= ldaIdx; k--) {
                     AsmLine lk = allLines.get(k).line;
                     if(lk instanceof AsmInstruction) {
                        AsmInstruction ik = (AsmInstruction) lk;
                        CpuOpcode opk = ik.getCpuOpcode();
                        if(opk == null) continue;
                        String mk = opk.getMnemonic();
                        if(curAxs == -1) {
                           if(mk.equals("axs")) {
                              curAxs = k;
                           } else if(clobbersX(ik)) {
                              xSafe = false;
                              break;
                           }
                        } else {
                           if(mk.equals("lax") || mk.equals("ldx")) {
                              String var2 = ik.getOperand1();
                              if(canonVar1.equals(resolve.apply(var2, currentScope))) {
                                 curLax = k;
                                 break;
                              }
                           } else if(clobbersX(ik)) {
                              xSafe = false;
                              break;
                           }
                        }
                     }
                  }
                  if(xSafe && curAxs != -1 && curLax != -1) {
                     axsIdx = curAxs;
                     laxIdx = curLax;
                     break;
                  }
               }
            }
         }
         if(branchIdx == -1 || axsIdx == -1 || laxIdx == -1) continue;

         // 5. Find pre-loop store: look backwards before headLabel for STA var
         int staIdx = -1;
         boolean preXSafe = true;
         for(int j = i - 1; j >= 0; j--) {
            AsmLine l = allLines.get(j).line;
            if(l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) break;
            if(l instanceof AsmInstruction) {
               AsmInstruction inst = (AsmInstruction) l;
               CpuOpcode op = inst.getCpuOpcode();
               if(op == null) continue;
               String mnem = op.getMnemonic();
               if(mnem.equals("sta")) {
                  String staVar = inst.getOperand1();
                  if(canonVar1.equals(resolve.apply(staVar, currentScope))) {
                     staIdx = j;
                     break;
                  }
               }
               if(clobbersX(inst)) {
                  preXSafe = false;
                  break;
               }
            }
         }
         if(staIdx == -1 || !preXSafe) continue;

         // 6. Apply transformations!
         getLog().append("Pass5: Pinned loop register X for variable " + var1 + " in loop " + headName);

         // A. Insert TAX immediately after STA var in pre-loop
         LineRef staRef = allLines.get(staIdx);
         int inChunkStaIdx = staRef.chunk.getLines().indexOf(staRef.line);
         AsmInstruction taxInst = new AsmInstruction(taxOp, null, null, null);
         staRef.chunk.getLines().add(inChunkStaIdx + 1, taxInst);

         // B. At loop head: replace LDA arr,y with LDA arr,x and remove LDY var
         LineRef ldaRef = allLines.get(ldaIdx);
         String arrOperand = ldaInst.getOperand1();
         if(arrOperand != null && arrOperand.contains(",")) {
            arrOperand = arrOperand.substring(0, arrOperand.indexOf(',')).trim();
         }
         AsmInstruction newLda = new AsmInstruction(ldaXOp, arrOperand, null, null);
         int inChunkLdaIdx = ldaRef.chunk.getLines().indexOf(ldaRef.line);
         ldaRef.chunk.getLines().set(inChunkLdaIdx, newLda);

         LineRef ldyRef = allLines.get(ldyIdx);
         ldyRef.chunk.getLines().remove(ldyRef.line);

         // C. At loop step: replace LAX var with TXA
         LineRef laxRef = allLines.get(laxIdx);
         AsmInstruction txaInst = new AsmInstruction(txaOp, null, null, null);
         int inChunkLaxIdx = laxRef.chunk.getLines().indexOf(laxRef.line);
         laxRef.chunk.getLines().set(inChunkLaxIdx, txaInst);

         overallModified = true;
         // Restart search
         break;
      }

      return overallModified;
   }
}
