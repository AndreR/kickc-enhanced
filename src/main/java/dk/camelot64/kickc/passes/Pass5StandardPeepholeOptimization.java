package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * CPU-independent standard MOS 6502/65C02/6510 assembler peephole optimizations.
 * Extracted from Pass5IllegalOpcodeOptimization (TD-4) so official 6502 peepholes
 * execute on all target CPUs regardless of MOS6502_UNODC support.
 */
public class Pass5StandardPeepholeOptimization extends Pass5AsmOptimization {

   public Pass5StandardPeepholeOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = getSharedAllLines();
         Map<String, CanonicalResolver> scopeResolvers = getSharedScopeResolvers(allLines);
         Map<String, Map<String, String>> scopeAliasMap = null;

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            String currentScope = ref1.scope;
            if(!isScopeDirty(currentScope)) {
               continue;
            }
            if(!(ref1.line instanceof AsmInstruction)) {
               continue;
            }
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.isNoOptimize() || inst1.getCpuOpcode() == null) {
               continue;
            }

            int nextIdx = findNextInstruction(allLines, i + 1);
            if(nextIdx == -1) {
               continue;
            }
            LineRef ref2 = allLines.get(nextIdx);
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.isNoOptimize() || inst2.getCpuOpcode() == null) {
               continue;
            }

            int branchIdx = findNextInstruction(allLines, nextIdx + 1);
            AsmInstruction inst3 = branchIdx != -1 ? (AsmInstruction) allLines.get(branchIdx).line : null;

            CpuOpcode op1 = inst1.getCpuOpcode();
            CpuOpcode op2 = inst2.getCpuOpcode();
            String mnem1 = op1.getMnemonic();
            String mnem2 = op2.getMnemonic();
            CpuAddressingMode mode1 = op1.getAddressingMode();
            CpuAddressingMode mode2 = op2.getAddressingMode();
            CanonicalResolver resolver = scopeResolvers.getOrDefault(currentScope, scopeResolvers.get(""));

            // -------------------------------------------------------------
            // Pattern 0a: Unsigned CMP #1 / CPX #1 / CPY #1 before BCC / BCS
            // On unsigned numbers: (x < 1) <==> (x == 0) and (x >= 1) <==> (x != 0).
            // -------------------------------------------------------------
            if(inst3 != null && !inst3.isNoOptimize() && mode2.equals(CpuAddressingMode.IMM) && isOneOperand(inst2.getOperand1())) {
               String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
               if(mnem3.equals("bcc") || mnem3.equals("bcs")) {
                  int afterBranchIdx = findNextInstruction(allLines, branchIdx + 1);
                  if(afterBranchIdx != -1) {
                     AsmInstruction instAfter = (AsmInstruction) allLines.get(afterBranchIdx).line;
                     if(instAfter.getCpuOpcode() != null) {
                        String mnemAfter = instAfter.getCpuOpcode().getMnemonic();
                        if(mnemAfter.startsWith("b")) {
                           continue;
                        }
                     }
                  }
                  String newBranchMnem = mnem3.equals("bcc") ? "beq" : "bne";
                  CpuOpcode newBranchOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(newBranchMnem, CpuAddressingMode.REL, false);
                  if(newBranchOp != null) {
                     String cmpReg = mnem2.equals("cmp") ? "A" : (mnem2.equals("cpx") ? "X" : (mnem2.equals("cpy") ? "Y" : null));
                     boolean canEliminateCmp = cmpReg != null && areFlagsEstablishedForReg(allLines, nextIdx, cmpReg, currentScope);

                     if(canEliminateCmp) {
                        getLog().append("Eliminating redundant " + inst2.getAsm() + " and converting " + mnem3 + " to " + newBranchMnem);
                        inst3.setCpuOpcode(newBranchOp);
                        tombstoneLine(allLines, nextIdx);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        continue;
                     } else {
                        getLog().append("Converting " + inst2.getAsm() + "; " + mnem3 + " to CMP #0; " + newBranchMnem);
                        inst2.setOperand1("0");
                        inst3.setCpuOpcode(newBranchOp);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        continue;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0e: 8-to-16 cast followed by << 8 on same variable
            // sta X; lda #0; sta X+1; lda Y; sta Y+1; lda #0; sta Y (where X == Y)
            // -> sta Y+1; lda #0; sta Y
            // -------------------------------------------------------------
            if(mnem1.equals("sta")) {
               int i2 = nextIdx;
               int i3 = findNextInstruction(allLines, i2 + 1);
               int i4 = i3 != -1 ? findNextInstruction(allLines, i3 + 1) : -1;
               int i5 = i4 != -1 ? findNextInstruction(allLines, i4 + 1) : -1;
               int i6 = i5 != -1 ? findNextInstruction(allLines, i5 + 1) : -1;
               int i7 = i6 != -1 ? findNextInstruction(allLines, i6 + 1) : -1;
               if(i7 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                  AsmInstruction instC = (AsmInstruction) allLines.get(i4).line;
                  AsmInstruction instD = (AsmInstruction) allLines.get(i5).line;
                  AsmInstruction instE = (AsmInstruction) allLines.get(i6).line;
                  AsmInstruction instF = (AsmInstruction) allLines.get(i7).line;

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("lda") && isZeroOperand(instA.getOperand1()) &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("sta") &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("lda") &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("sta") &&
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("lda") && isZeroOperand(instE.getOperand1()) &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("sta")) {

                     if(scopeAliasMap == null) scopeAliasMap = buildScopeAliasMap(allLines);
                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     String var1 = getBaseVar(inst1.getOperand1());
                     String varB = getBaseVar(instB.getOperand1());
                     String varC = getBaseVar(instC.getOperand1());
                     String varD = getBaseVar(instD.getOperand1());
                     String varF = getBaseVar(instF.getOperand1());

                     if(getOffset(inst1.getOperand1()) == 0 && getOffset(instB.getOperand1()) == 1 &&
                        getOffset(instC.getOperand1()) == 0 && getOffset(instD.getOperand1()) == 1 && getOffset(instF.getOperand1()) == 0 &&
                        isSameLocation(var1, varB, aliases) &&
                        isSameLocation(varC, varD, aliases) &&
                        isSameLocation(varC, varF, aliases) &&
                        isSameLocation(var1, varC, aliases) &&
                        !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram())) {

                        getLog().append("Folding 8-to-16 cast and << 8 on " + varC);
                        inst1.setOperand1(instD.getOperand1());
                        instB.setOperand1(instF.getOperand1());
                        tombstoneLine(allLines, i4);
                        tombstoneLine(allLines, i5);
                        tombstoneLine(allLines, i6);
                        tombstoneLine(allLines, i7);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        continue;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0f: Register shuffle cascade
            // tay; txa; sty.z ZP; ldx.z ZP; tay; txa; sty.z TARGET
            // -> stx.z TARGET
            // -------------------------------------------------------------
            if(mnem1.equals("tay")) {
               int i2 = nextIdx;
               int i3 = findNextInstruction(allLines, i2 + 1);
               int i4 = i3 != -1 ? findNextInstruction(allLines, i3 + 1) : -1;
               int i5 = i4 != -1 ? findNextInstruction(allLines, i4 + 1) : -1;
               int i6 = i5 != -1 ? findNextInstruction(allLines, i5 + 1) : -1;
               int i7 = i6 != -1 ? findNextInstruction(allLines, i6 + 1) : -1;
               if(i7 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                  AsmInstruction instC = (AsmInstruction) allLines.get(i4).line;
                  AsmInstruction instD = (AsmInstruction) allLines.get(i5).line;
                  AsmInstruction instE = (AsmInstruction) allLines.get(i6).line;
                  AsmInstruction instF = (AsmInstruction) allLines.get(i7).line;

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("txa") &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("sty") &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("ldx") &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("tay") &&
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("txa") &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("sty")) {

                     String opScratch = instB.getOperand1();
                     if(opScratch != null && opScratch.equals(instC.getOperand1()) &&
                        !isVolatileOrHardwareOperand(opScratch, currentScope, resolver, getProgram())) {
                        String targetOp = instF.getOperand1();
                        CpuAddressingMode targetMode = instF.getCpuOpcode().getAddressingMode();
                        CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", targetMode, false);
                        if(stxOp != null) {
                           getLog().append("Collapsing register shuffle cascade to STX " + targetOp);
                           inst1.setCpuOpcode(stxOp);
                           inst1.setOperand1(targetOp);
                           tombstoneLine(allLines, i2);
                           tombstoneLine(allLines, i3);
                           tombstoneLine(allLines, i4);
                           tombstoneLine(allLines, i5);
                           tombstoneLine(allLines, i6);
                           tombstoneLine(allLines, i7);
                           markScopeDirty(currentScope);
                           passModified = true;
                           overallModified = true;
                           continue;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0g: Right-shift high-byte extraction before RTS or store
            // lda.z X+1; sta.z TEMP; lda #0; sta.z TEMP+1; lda.z TEMP; (rts | sta DEST)
            // -> lda.z X+1; (rts | sta DEST)
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && getOffset(inst1.getOperand1()) == 1) {
               int i2 = nextIdx;
               int i3 = findNextInstruction(allLines, i2 + 1);
               int i4 = i3 != -1 ? findNextInstruction(allLines, i3 + 1) : -1;
               int i5 = i4 != -1 ? findNextInstruction(allLines, i4 + 1) : -1;
               int i6 = i5 != -1 ? findNextInstruction(allLines, i5 + 1) : -1;
               if(i6 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                  AsmInstruction instC = (AsmInstruction) allLines.get(i4).line;
                  AsmInstruction instD = (AsmInstruction) allLines.get(i5).line;
                  AsmInstruction instE = (AsmInstruction) allLines.get(i6).line;

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("sta") &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("lda") && isZeroOperand(instB.getOperand1()) &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("sta") &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("lda") &&
                     instE.getCpuOpcode() != null) {

                     if(scopeAliasMap == null) scopeAliasMap = buildScopeAliasMap(allLines);
                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     String baseA = getBaseVar(instA.getOperand1());
                     String baseC = getBaseVar(instC.getOperand1());
                     String baseD = getBaseVar(instD.getOperand1());

                     if(getOffset(instA.getOperand1()) == 0 && getOffset(instC.getOperand1()) == 1 && getOffset(instD.getOperand1()) == 0 &&
                        isSameLocation(baseA, baseC, aliases) && isSameLocation(baseA, baseD, aliases) &&
                        !isVolatileOrHardwareOperand(instA.getOperand1(), currentScope, resolver, getProgram())) {

                        if(instE.getCpuOpcode().getMnemonic().equals("rts")) {
                           getLog().append("Eliminating redundant right-shift intermediate stores before RTS");
                           tombstoneLine(allLines, i2);
                           tombstoneLine(allLines, i3);
                           tombstoneLine(allLines, i4);
                           tombstoneLine(allLines, i5);
                           markScopeDirty(currentScope);
                           passModified = true;
                           overallModified = true;
                           continue;
                        } else if(instE.getCpuOpcode().getMnemonic().equals("sta")) {
                           getLog().append("Forwarding right-shift high-byte load directly to STA " + instE.getOperand1());
                           tombstoneLine(allLines, i2);
                           tombstoneLine(allLines, i3);
                           tombstoneLine(allLines, i4);
                           tombstoneLine(allLines, i5);
                           markScopeDirty(currentScope);
                           passModified = true;
                           overallModified = true;
                           continue;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0h: Elimination of intermediate $fd store/reload
            // ldy <IDX>; sty.z $fd; ldy <PTR>; sty.z $fe; ldy <PTR>+1; sty.z $ff; ldy.z $fd; (sta|lda) ($fe),y
            // -> ldy <PTR>; sty.z $fe; ldy <PTR>+1; sty.z $ff; ldy <IDX>; (sta|lda) ($fe),y
            // -------------------------------------------------------------
            if(mnem1.equals("ldy")) {
               int i2 = nextIdx;
               int i3 = findNextInstruction(allLines, i2 + 1);
               int i4 = i3 != -1 ? findNextInstruction(allLines, i3 + 1) : -1;
               int i5 = i4 != -1 ? findNextInstruction(allLines, i4 + 1) : -1;
               int i6 = i5 != -1 ? findNextInstruction(allLines, i5 + 1) : -1;
               int i7 = i6 != -1 ? findNextInstruction(allLines, i6 + 1) : -1;
               int i8 = i7 != -1 ? findNextInstruction(allLines, i7 + 1) : -1;
               if(i8 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                  AsmInstruction instC = (AsmInstruction) allLines.get(i4).line;
                  AsmInstruction instD = (AsmInstruction) allLines.get(i5).line;
                  AsmInstruction instE = (AsmInstruction) allLines.get(i6).line;
                  AsmInstruction instF = (AsmInstruction) allLines.get(i7).line;
                  AsmInstruction instG = (AsmInstruction) allLines.get(i8).line;

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("sty") && isZpAddressOperand(instA.getOperand1(), "$fd") &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("ldy") &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("sty") && isZpAddressOperand(instC.getOperand1(), "$fe") &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("ldy") &&
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("sty") && isZpAddressOperand(instE.getOperand1(), "$ff") &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("ldy") && isZpAddressOperand(instF.getOperand1(), "$fd") &&
                     instG.getCpuOpcode() != null && (instG.getCpuOpcode().getMnemonic().equals("sta") || instG.getCpuOpcode().getMnemonic().equals("lda")) &&
                     instG.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY)) {

                     String idxOp = inst1.getOperand1();
                     CpuAddressingMode idxMode = inst1.getCpuOpcode().getAddressingMode();
                     if(!isZpAddressOperand(idxOp, "$fe") && !isZpAddressOperand(idxOp, "$ff") && !isZpAddressOperand(idxOp, "$fd") &&
                        !isVolatileOrHardwareOperand(idxOp, currentScope, resolver, getProgram())) {
                        getLog().append("Eliminating $fd intermediate save/restore for indirect indexed deref");
                        inst1.setCpuOpcode(instB.getCpuOpcode());
                        inst1.setOperand1(instB.getOperand1());
                        instA.setCpuOpcode(instC.getCpuOpcode());
                        instA.setOperand1(instC.getOperand1());
                        instB.setCpuOpcode(instD.getCpuOpcode());
                        instB.setOperand1(instD.getOperand1());
                        instC.setCpuOpcode(instE.getCpuOpcode());
                        instC.setOperand1(instE.getOperand1());
                        CpuOpcode ldyIdxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldy", idxMode, false);
                        instD.setCpuOpcode(ldyIdxOp);
                        instD.setOperand1(idxOp);
                        tombstoneLine(allLines, i6);
                        tombstoneLine(allLines, i7);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        continue;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0i: 8-to-16 cast followed by << 6 on same variable
            // -------------------------------------------------------------
            if(mnem1.equals("sta") && getOffset(inst1.getOperand1()) == 0) {
               int i2 = nextIdx;
               int i3 = findNextInstruction(allLines, i2 + 1);
               int i4 = i3 != -1 ? findNextInstruction(allLines, i3 + 1) : -1;
               int i5 = i4 != -1 ? findNextInstruction(allLines, i4 + 1) : -1;
               int i6 = i5 != -1 ? findNextInstruction(allLines, i5 + 1) : -1;
               int i7 = i6 != -1 ? findNextInstruction(allLines, i6 + 1) : -1;
               int i8 = i7 != -1 ? findNextInstruction(allLines, i7 + 1) : -1;
               int i9 = i8 != -1 ? findNextInstruction(allLines, i8 + 1) : -1;
               int i10 = i9 != -1 ? findNextInstruction(allLines, i9 + 1) : -1;
               int i11 = i10 != -1 ? findNextInstruction(allLines, i10 + 1) : -1;
               int i12 = i11 != -1 ? findNextInstruction(allLines, i11 + 1) : -1;
               int i13 = i12 != -1 ? findNextInstruction(allLines, i12 + 1) : -1;
               int i14 = i13 != -1 ? findNextInstruction(allLines, i13 + 1) : -1;
               int i15 = i14 != -1 ? findNextInstruction(allLines, i14 + 1) : -1;
               if(i15 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                  AsmInstruction instC = (AsmInstruction) allLines.get(i4).line;
                  AsmInstruction instD = (AsmInstruction) allLines.get(i5).line;
                  AsmInstruction instE = (AsmInstruction) allLines.get(i6).line;
                  AsmInstruction instF = (AsmInstruction) allLines.get(i7).line;
                  AsmInstruction instG = (AsmInstruction) allLines.get(i8).line;
                  AsmInstruction instH = (AsmInstruction) allLines.get(i9).line;
                  AsmInstruction instI = (AsmInstruction) allLines.get(i10).line;
                  AsmInstruction instJ = (AsmInstruction) allLines.get(i11).line;
                  AsmInstruction instK = (AsmInstruction) allLines.get(i12).line;
                  AsmInstruction instL = (AsmInstruction) allLines.get(i13).line;
                  AsmInstruction instM = (AsmInstruction) allLines.get(i14).line;
                  AsmInstruction instN = (AsmInstruction) allLines.get(i15).line;

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("lda") && isZeroOperand(instA.getOperand1()) &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instB.getOperand1()) == 1 &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("lda") && getOffset(instC.getOperand1()) == 1 &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("lsr") &&
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("sta") && isZpAddressOperand(instE.getOperand1(), "$ff") &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("lda") && getOffset(instF.getOperand1()) == 0 &&
                     instG.getCpuOpcode() != null && instG.getCpuOpcode().getMnemonic().equals("ror") &&
                     instH.getCpuOpcode() != null && instH.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instH.getOperand1()) == 1 &&
                     instI.getCpuOpcode() != null && instI.getCpuOpcode().getMnemonic().equals("lda") && isZeroOperand(instI.getOperand1()) &&
                     instJ.getCpuOpcode() != null && instJ.getCpuOpcode().getMnemonic().equals("ror") &&
                     instK.getCpuOpcode() != null && instK.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instK.getOperand1()) == 0 &&
                     instL.getCpuOpcode() != null && instL.getCpuOpcode().getMnemonic().equals("lsr") && isZpAddressOperand(instL.getOperand1(), "$ff") &&
                     instM.getCpuOpcode() != null && instM.getCpuOpcode().getMnemonic().equals("ror") && getOffset(instM.getOperand1()) == 1 &&
                     instN.getCpuOpcode() != null && instN.getCpuOpcode().getMnemonic().equals("ror") && getOffset(instN.getOperand1()) == 0) {

                     if(scopeAliasMap == null) scopeAliasMap = buildScopeAliasMap(allLines);
                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     String var1 = getBaseVar(inst1.getOperand1());
                     String varB = getBaseVar(instB.getOperand1());
                     String varC = getBaseVar(instC.getOperand1());
                     String varF = getBaseVar(instF.getOperand1());
                     String varH = getBaseVar(instH.getOperand1());
                     String varK = getBaseVar(instK.getOperand1());
                     String varM = getBaseVar(instM.getOperand1());
                     String varN = getBaseVar(instN.getOperand1());

                     if(isSameLocation(var1, varB, aliases) && isSameLocation(var1, varC, aliases) &&
                        isSameLocation(var1, varF, aliases) && isSameLocation(var1, varH, aliases) &&
                        isSameLocation(var1, varK, aliases) && isSameLocation(var1, varM, aliases) &&
                        isSameLocation(var1, varN, aliases) &&
                        !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram())) {

                        getLog().append("Folding 8-to-16 cast and << 6 on " + varC);
                        inst1.setOperand1(instH.getOperand1());
                        CpuOpcode lsrOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lsr", instH.getCpuOpcode().getAddressingMode(), false);
                        instB.setCpuOpcode(lsrOp);
                        instB.setOperand1(instH.getOperand1());
                        CpuOpcode rorNonOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ror", CpuAddressingMode.NON, false);
                        instC.setCpuOpcode(rorNonOp);
                        instC.setOperand1(null);
                        instD.setCpuOpcode(lsrOp);
                        instD.setOperand1(instH.getOperand1());
                        instE.setCpuOpcode(rorNonOp);
                        instE.setOperand1(null);
                        instF.setCpuOpcode(instK.getCpuOpcode());
                        instF.setOperand1(instK.getOperand1());

                        tombstoneLine(allLines, i8);
                        tombstoneLine(allLines, i9);
                        tombstoneLine(allLines, i10);
                        tombstoneLine(allLines, i11);
                        tombstoneLine(allLines, i12);
                        tombstoneLine(allLines, i13);
                        tombstoneLine(allLines, i14);
                        tombstoneLine(allLines, i15);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        continue;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0k: Redundant TXA elimination
            // -------------------------------------------------------------
            if(mnem1.equals("txa")) {
               boolean removedTxa = false;
               for(int k = nextIdx; k < allLines.size(); k++) {
                  LineRef cand = allLines.get(k);
                  if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
                  if(cand.line instanceof AsmLabel || cand.line instanceof AsmScopeBegin || cand.line instanceof AsmScopeEnd) break;
                  if(cand.line instanceof AsmInstruction) {
                     AsmInstruction instK = (AsmInstruction) cand.line;
                     CpuOpcode opK = instK.getCpuOpcode();
                     if(opK == null) break;
                     String mnemK = opK.getMnemonic();
                     if(mnemK.equals("txa")) {
                        getLog().append("Eliminating redundant secondary TXA");
                        tombstoneLine(allLines, k);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        removedTxa = true;
                        break;
                     }
                     if(mnemK.equals("lda") || mnemK.equals("pla") || mnemK.equals("tax") || mnemK.equals("tay") ||
                        mnemK.equals("adc") || mnemK.equals("sbc") || mnemK.equals("and") || mnemK.equals("ora") ||
                        mnemK.equals("eor") || mnemK.equals("asl") || mnemK.equals("lsr") || mnemK.equals("rol") ||
                        mnemK.equals("ror") || mnemK.equals("alr") || mnemK.equals("anc") || mnemK.equals("arr") ||
                        mnemK.equals("axs") || mnemK.equals("dcp") || mnemK.equals("isc") || mnemK.equals("rla") ||
                        mnemK.equals("rra") || mnemK.equals("slo") || mnemK.equals("sre") || mnemK.equals("lax") ||
                        mnemK.equals("jsr") || mnemK.equals("rti") || mnemK.equals("rts") || mnemK.equals("jmp") || mnemK.startsWith("b")) {
                        break;
                     }
                     if(mnemK.equals("ldx") || mnemK.equals("inx") || mnemK.equals("dex") || mnemK.equals("tsx")) {
                        break;
                     }
                     continue;
                  }
                  break;
               }
               if(removedTxa) continue;
            }

            // -------------------------------------------------------------
            // Pattern 0l: 16-bit transitive copy chain fold
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && getOffset(inst1.getOperand1()) == 0) {
               int i2 = nextIdx;
               int i3 = findNextInstruction(allLines, i2 + 1);
               int i4 = i3 != -1 ? findNextInstruction(allLines, i3 + 1) : -1;
               int i5 = i4 != -1 ? findNextInstruction(allLines, i4 + 1) : -1;
               int i6 = i5 != -1 ? findNextInstruction(allLines, i5 + 1) : -1;
               int i7 = i6 != -1 ? findNextInstruction(allLines, i6 + 1) : -1;
               int i8 = i7 != -1 ? findNextInstruction(allLines, i7 + 1) : -1;
               if(i8 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                  AsmInstruction instC = (AsmInstruction) allLines.get(i4).line;
                  AsmInstruction instD = (AsmInstruction) allLines.get(i5).line;
                  AsmInstruction instE = (AsmInstruction) allLines.get(i6).line;
                  AsmInstruction instF = (AsmInstruction) allLines.get(i7).line;
                  AsmInstruction instG = (AsmInstruction) allLines.get(i8).line;

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instA.getOperand1()) == 0 &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("lda") && getOffset(instB.getOperand1()) == 1 &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instC.getOperand1()) == 1 &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("lda") && getOffset(instD.getOperand1()) == 0 &&
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instE.getOperand1()) == 0 &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("lda") && getOffset(instF.getOperand1()) == 1 &&
                     instG.getCpuOpcode() != null && instG.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instG.getOperand1()) == 1) {

                     if(scopeAliasMap == null) scopeAliasMap = buildScopeAliasMap(allLines);
                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     String baseSrcLo = getBaseVar(inst1.getOperand1());
                     String baseSrcHi = getBaseVar(instB.getOperand1());
                     String baseD1Lo = getBaseVar(instA.getOperand1());
                     String baseD1Hi = getBaseVar(instC.getOperand1());
                     String baseD1ReadLo = getBaseVar(instD.getOperand1());
                     String baseD1ReadHi = getBaseVar(instF.getOperand1());
                     String baseD2Lo = getBaseVar(instE.getOperand1());
                     String baseD2Hi = getBaseVar(instG.getOperand1());

                     if(isSameLocation(baseSrcLo, baseSrcHi, aliases) &&
                        isSameLocation(baseD1Lo, baseD1Hi, aliases) &&
                        isSameLocation(baseD1Lo, baseD1ReadLo, aliases) &&
                        isSameLocation(baseD1Lo, baseD1ReadHi, aliases) &&
                        isSameLocation(baseD2Lo, baseD2Hi, aliases) &&
                        !isSameLocation(baseSrcLo, baseD1Lo, aliases) &&
                        !isSameLocation(baseD1Lo, baseD2Lo, aliases) &&
                        !isSameLocation(baseSrcLo, baseD2Lo, aliases) &&
                        !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram()) &&
                        !isVolatileOrHardwareOperand(instA.getOperand1(), currentScope, resolver, getProgram()) &&
                        !isVolatileOrHardwareOperand(instE.getOperand1(), currentScope, resolver, getProgram())) {

                        getLog().append("Folding 16-bit copy chain " + baseSrcLo + " -> " + baseD1Lo + " -> " + baseD2Lo);
                        CpuOpcode staModeLo = instE.getCpuOpcode();
                        String staOpLo = instE.getOperand1();
                        CpuOpcode staModeHi = instG.getCpuOpcode();
                        String staOpHi = instG.getOperand1();
                        AsmInstruction newStaLo = new AsmInstruction(staModeLo, staOpLo, null, null);
                        AsmInstruction newStaHi = new AsmInstruction(staModeHi, staOpHi, null, null);

                        int idx2 = allLines.get(i2).chunk.getLines().indexOf(instA);
                        allLines.get(i2).chunk.getLines().add(idx2 + 1, newStaLo);
                        int idx4 = allLines.get(i4).chunk.getLines().indexOf(instC);
                        allLines.get(i4).chunk.getLines().add(idx4 + 1, newStaHi);

                        tombstoneLine(allLines, i5);
                        tombstoneLine(allLines, i6);
                        tombstoneLine(allLines, i7);
                        tombstoneLine(allLines, i8);
                        markScopeDirty(currentScope);
                        invalidateRoundStructure();
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0: Redundant CMP #0 / CPX #0 / CPY #0 elimination (FL-03)
            // Also shorten remaining CPX #0 / CPY #0 -> TXA / TYA when A and C are dead
            // -------------------------------------------------------------
            if(mode1.equals(CpuAddressingMode.IMM) && isZeroOperand(inst1.getOperand1())) {
               String reg = mnem1.equals("cmp") ? "A" : (mnem1.equals("cpx") ? "X" : (mnem1.equals("cpy") ? "Y" : null));
               if(reg != null && inst2 != null && !inst2.isNoOptimize() && inst2.getCpuOpcode() != null) {
                  String m2 = inst2.getCpuOpcode().getMnemonic();
                  boolean isZOrNBranch = m2.equals("beq") || m2.equals("bne") || m2.equals("bmi") || m2.equals("bpl");
                  if(isZOrNBranch && areFlagsEstablishedForReg(allLines, i, reg, currentScope)) {
                     getLog().append("Eliminating redundant zero comparison " + inst1.getAsm() + " before " + inst2.getAsm());
                     tombstoneLine(allLines, i);
                     markScopeDirty(currentScope);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
                  if(isZOrNBranch && (mnem1.equals("cpx") || mnem1.equals("cpy")) && !isFlagCLiveAfter(allLines, i, currentScope, getProgram())) {
                     int targetIdx = findLabelIndex(allLines, inst2.getOperand1(), nextIdx);
                     boolean targetADead = targetIdx != -1 && isRegDeadAlongPath("A", allLines, targetIdx + 1, new HashSet<>(), currentScope, getProgram());
                     boolean fallADead = isRegDeadAlongPath("A", allLines, nextIdx + 1, new HashSet<>(), currentScope, getProgram());
                     if(targetADead && fallADead) {
                        String xferMnem = mnem1.equals("cpx") ? "txa" : "tya";
                        CpuOpcode xferOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(xferMnem, CpuAddressingMode.NON, false);
                        if(xferOp != null) {
                           getLog().append("Shortening " + inst1.getAsm() + " to " + xferMnem + " before " + inst2.getAsm());
                           inst1.setCpuOpcode(xferOp);
                           inst1.setOperand1(null);
                           markScopeDirty(currentScope);
                           passModified = true;
                           overallModified = true;
                           continue;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0b: Redundant LDA/LDX/LDY <mem> after memory RMW (DEC/INC/ASL/LSR/ROL/ROR <mem>) (FL-03)
            // -------------------------------------------------------------
            boolean isMemRmw = !mode1.equals(CpuAddressingMode.NON) &&
               (mnem1.equals("dec") || mnem1.equals("inc") || mnem1.equals("asl") || mnem1.equals("lsr") || mnem1.equals("rol") || mnem1.equals("ror"));
            String loadReg2 = mnem2.equals("lda") ? "A" : (mnem2.equals("ldx") ? "X" : (mnem2.equals("ldy") ? "Y" : null));
            if(inst3 != null && !inst3.isNoOptimize() && branchIdx != -1 &&
               isMemRmw && loadReg2 != null &&
               inst1.getOperand1() != null && inst2.getOperand1() != null && mode1.equals(mode2) &&
               (inst1.getOperand1().equals(inst2.getOperand1())
                  || (resolver != null && resolver.resolve(inst1.getOperand1()).equals(resolver.resolve(inst2.getOperand1())))) &&
               !isVolatileOrHardwareOperand(inst2.getOperand1(), currentScope, resolver, getProgram())) {
               String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
               boolean isZOrNBranch = mnem3.equals("beq") || mnem3.equals("bne") || mnem3.equals("bmi") || mnem3.equals("bpl");
               if(isZOrNBranch) {
                  int targetIdx = findLabelIndex(allLines, inst3.getOperand1(), branchIdx);
                  boolean targetDead = targetIdx != -1 && isRegDeadAlongPath(loadReg2, allLines, targetIdx + 1, new HashSet<>(), currentScope, getProgram());
                  boolean fallthroughDead = isRegDeadAlongPath(loadReg2, allLines, branchIdx + 1, new HashSet<>(), currentScope, getProgram());
                  if(targetDead && fallthroughDead) {
                     getLog().append("Eliminating redundant load " + inst2.getAsm() + " after " + inst1.getAsm() + " before " + inst3.getAsm());
                     tombstoneLine(allLines, nextIdx);
                     markScopeDirty(currentScope);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0c: Redundant EOR #0
            // -------------------------------------------------------------
            if(mnem2.equals("eor") && mode2.equals(CpuAddressingMode.IMM) && isZeroOperand(inst2.getOperand1())) {
               if(mnem1.equals("lda") || mnem1.equals("pla") || mnem1.equals("txa") || mnem1.equals("tya") ||
                  mnem1.equals("and") || mnem1.equals("ora") || mnem1.equals("adc") || mnem1.equals("sbc") ||
                  mnem1.equals("asl") || mnem1.equals("lsr") || mnem1.equals("rol") || mnem1.equals("ror") ||
                  mnem1.equals("lax") || mnem1.equals("alr")) {
                  getLog().append("Eliminating redundant " + inst2.getAsm() + " after " + inst1.getAsm());
                  tombstoneLine(allLines, nextIdx);
                  markScopeDirty(currentScope);
                  passModified = true;
                  overallModified = true;
                  continue;
               }
            }

            // -------------------------------------------------------------
            // Pattern 0d: Redundant roundtrip transfers TAX; TXA, TXA; TAX, TAY; TYA, TYA; TAY
            // -------------------------------------------------------------
            if(((mnem1.equals("tax") && mnem2.equals("txa")) ||
                (mnem1.equals("txa") && mnem2.equals("tax")) ||
                (mnem1.equals("tay") && mnem2.equals("tya")) ||
                (mnem1.equals("tya") && mnem2.equals("tay"))) &&
               mode1.equals(CpuAddressingMode.NON) && mode2.equals(CpuAddressingMode.NON)) {
               getLog().append("Eliminating redundant transfer " + inst2.getAsm() + " after " + inst1.getAsm());
               tombstoneLine(allLines, nextIdx);
               markScopeDirty(currentScope);
               passModified = true;
               overallModified = true;
               continue;
            }

            // -------------------------------------------------------------
            // Pattern 0d2 (FL-04): Transfer-Store folding (TXA; STA dst -> STX dst, TYA; STA dst -> STY dst)
            // -------------------------------------------------------------
            if((mnem1.equals("txa") || mnem1.equals("tya")) && mode1.equals(CpuAddressingMode.NON) &&
               mnem2.equals("sta")) {
               String srcReg = mnem1.equals("txa") ? "X" : "Y";
               String storeMnem = mnem1.equals("txa") ? "stx" : "sty";
               boolean validMode = mnem1.equals("txa")
                     ? (mode2.equals(CpuAddressingMode.ZP) || mode2.equals(CpuAddressingMode.ABS) || mode2.equals(CpuAddressingMode.ZPY))
                     : (mode2.equals(CpuAddressingMode.ZP) || mode2.equals(CpuAddressingMode.ABS) || mode2.equals(CpuAddressingMode.ZPX));
               if(validMode && isRegDeadAlongPath("A", allLines, nextIdx + 1, new HashSet<>(), currentScope, getProgram())) {
                  boolean nzSafe = (getLiveFlagsAfter(allLines, nextIdx, currentScope, getProgram()) & FLAG_NZ) == 0
                        || areFlagsEstablishedForReg(allLines, i, srcReg, currentScope);
                  if(nzSafe) {
                     CpuOpcode storeOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(storeMnem, mode2, false);
                     if(storeOp != null) {
                        getLog().append("Folding " + inst1.getAsm() + "; " + inst2.getAsm() + " -> " + storeMnem + " " + inst2.getOperand1());
                        inst2.setCpuOpcode(storeOp);
                        tombstoneLine(allLines, i);
                        markScopeDirty(currentScope);
                        passModified = true;
                        overallModified = true;
                        continue;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0d3 (FL-04): Load-Transfer folding (LDA src; TAY -> LDY src, LDA src; TAX -> LDX src)
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && (mnem2.equals("tay") || mnem2.equals("tax")) && mode2.equals(CpuAddressingMode.NON)) {
               String loadMnem = mnem2.equals("tay") ? "ldy" : "ldx";
               boolean validMode = mnem2.equals("tay")
                     ? (mode1.equals(CpuAddressingMode.IMM) || mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS) || mode1.equals(CpuAddressingMode.ZPX) || mode1.equals(CpuAddressingMode.ABX))
                     : (mode1.equals(CpuAddressingMode.IMM) || mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS) || mode1.equals(CpuAddressingMode.ZPY) || mode1.equals(CpuAddressingMode.ABY));
               if(validMode && isRegDeadAlongPath("A", allLines, nextIdx + 1, new HashSet<>(), currentScope, getProgram())) {
                  CpuOpcode loadOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(loadMnem, mode1, false);
                  if(loadOp != null) {
                     getLog().append("Folding " + inst1.getAsm() + "; " + inst2.getAsm() + " -> " + loadMnem + " " + inst1.getOperand1());
                     inst1.setCpuOpcode(loadOp);
                     tombstoneLine(allLines, nextIdx);
                     markScopeDirty(currentScope);
                     passModified = true;
                     overallModified = true;
                     continue;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0d4 (FL-04): Dead register transfer elimination (TAX/TAY/TXA/TYA/TSX)
            // -------------------------------------------------------------
            if(mode1.equals(CpuAddressingMode.NON) &&
               (mnem1.equals("tax") || mnem1.equals("tay") || mnem1.equals("txa") || mnem1.equals("tya") || mnem1.equals("tsx"))) {
               String dstReg = (mnem1.equals("tax") || mnem1.equals("tsx")) ? "X" : (mnem1.equals("tay") ? "Y" : "A");
               if(isRegDeadAlongPath(dstReg, allLines, i + 1, new HashSet<>(), currentScope, getProgram())
                     && (getLiveFlagsAfter(allLines, i, currentScope, getProgram()) & FLAG_NZ) == 0) {
                  getLog().append("Eliminating dead register transfer " + inst1.getAsm());
                  tombstoneLine(allLines, i);
                  markScopeDirty(currentScope);
                  passModified = true;
                  overallModified = true;
                  continue;
               }
            }

            // -------------------------------------------------------------
            // Pattern 0e (dead overwritten load):
            // If inst1 is LDA/LDX/LDY (non-volatile/non-hardware IMM/ZP/ABS) and inst2 immediately
            // overwrites that register without reading flags or register, inst1 is dead.
            // -------------------------------------------------------------
            boolean isDeadOverwrittenLoad = false;
            if(!inst1.isNoOptimize() && !inst2.isNoOptimize() &&
               (mode1.equals(CpuAddressingMode.IMM) || mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
               !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram())) {
               if(mnem1.equals("lda") && (mnem2.equals("lda") || mnem2.equals("pla") || mnem2.equals("lax"))) {
                  isDeadOverwrittenLoad = true;
               } else if(mnem1.equals("ldx") && (mnem2.equals("ldx") || mnem2.equals("lax"))) {
                  isDeadOverwrittenLoad = true;
               } else if(mnem1.equals("ldy") && (mnem2.equals("ldy") || mnem2.equals("ply"))) {
                  isDeadOverwrittenLoad = true;
               }
            }
            if(isDeadOverwrittenLoad) {
               getLog().append("Eliminating dead overwritten load " + inst1.getAsm() + " before " + inst2.getAsm());
               tombstoneLine(allLines, i);
               markScopeDirty(currentScope);
               passModified = true;
               overallModified = true;
               continue;
            }

            // -------------------------------------------------------------
            // Pattern 0f (zero-extension before left shift):
            // sta V; lda #0; sta V+1; asl W; rol W+1 -> asl; sta V; lda #0; rol; sta V+1
            // -------------------------------------------------------------
            if(mnem1.equals("sta") && (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
               !isVolatileOrHardwareOperand(inst1.getOperand1(), currentScope, resolver, getProgram())) {
               String opV = inst1.getOperand1();
               int idx2 = nextIdx;
               LineRef r2 = allLines.get(idx2);
               AsmInstruction in2 = (AsmInstruction) r2.line;
               if(in2.getCpuOpcode() != null && "lda".equals(in2.getCpuOpcode().getMnemonic()) &&
                  AsmProgramStaticRegisterValues.matchImm(in2.getOperand1(), "0")) {
                  if(scopeAliasMap == null) scopeAliasMap = buildScopeAliasMap(allLines);
                  int idx3 = findNextInstruction(allLines, idx2 + 1);
                  if(idx3 != -1) {
                     LineRef r3 = allLines.get(idx3);
                     AsmInstruction in3 = (AsmInstruction) r3.line;
                     if(in3.getCpuOpcode() != null && "sta".equals(in3.getCpuOpcode().getMnemonic()) &&
                        isSameLocation(in3.getOperand1(), opV + "+1", scopeAliasMap.get(currentScope))) {
                        int idx4 = findNextInstruction(allLines, idx3 + 1);
                        if(idx4 != -1) {
                           LineRef r4 = allLines.get(idx4);
                           AsmInstruction in4 = (AsmInstruction) r4.line;
                           if(in4.getCpuOpcode() != null && "asl".equals(in4.getCpuOpcode().getMnemonic()) &&
                              isSameLocation(in4.getOperand1(), opV, scopeAliasMap.get(currentScope))) {
                              int idx5 = findNextInstruction(allLines, idx4 + 1);
                              if(idx5 != -1) {
                                 LineRef r5 = allLines.get(idx5);
                                 AsmInstruction in5 = (AsmInstruction) r5.line;
                                 if(in5.getCpuOpcode() != null && "rol".equals(in5.getCpuOpcode().getMnemonic()) &&
                                    isSameLocation(in5.getOperand1(), opV + "+1", scopeAliasMap.get(currentScope))) {
                                    getLog().append("Optimizing 16-bit zero-extension before shift: " + opV);
                                    CpuOpcode aslAcc = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("asl", CpuAddressingMode.NON, false);
                                    CpuOpcode rolAcc = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("rol", CpuAddressingMode.NON, false);
                                    AsmInstruction staV = new AsmInstruction(inst1.getCpuOpcode(), opV, null, null);
                                    inst1.setCpuOpcode(aslAcc);
                                    inst1.setOperand1(null);
                                    ref1.chunk.addLineAfter(inst1, staV);
                                    in4.setCpuOpcode(rolAcc);
                                    in4.setOperand1(null);
                                    in5.setCpuOpcode(in3.getCpuOpcode());
                                    in5.setOperand1(in3.getOperand1());
                                    tombstoneLine(allLines, idx3);
                                    markScopeDirty(currentScope);
                                    invalidateRoundStructure();
                                    passModified = true;
                                    overallModified = true;
                                    break;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0g (16-to-8 bit right shift by 2 folding):
            // lda HI; lsr; sta T+1; lda LO; ror; sta T; lsr T+1; ror T; lda T; sta DEST
            // -> lda HI; lsr; lda LO; ror; lsr; sta DEST
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS))) {
               int idx2 = nextIdx;
               LineRef r2 = allLines.get(idx2);
               AsmInstruction in2 = (AsmInstruction) r2.line;
               if(in2.getCpuOpcode() != null && "lsr".equals(in2.getCpuOpcode().getMnemonic()) &&
                  in2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                  int idx3 = findNextInstruction(allLines, idx2 + 1);
                  if(idx3 != -1) {
                     LineRef r3 = allLines.get(idx3);
                     AsmInstruction in3 = (AsmInstruction) r3.line;
                     if(in3.getCpuOpcode() != null && "sta".equals(in3.getCpuOpcode().getMnemonic())) {
                        String opT1 = in3.getOperand1();
                        if(opT1 != null && opT1.endsWith("+1")) {
                           String opT = opT1.substring(0, opT1.length() - 2);
                           int idx4 = findNextInstruction(allLines, idx3 + 1);
                           int idx5 = (idx4 != -1) ? findNextInstruction(allLines, idx4 + 1) : -1;
                           int idx6 = (idx5 != -1) ? findNextInstruction(allLines, idx5 + 1) : -1;
                           int idx7 = (idx6 != -1) ? findNextInstruction(allLines, idx6 + 1) : -1;
                           int idx8 = (idx7 != -1) ? findNextInstruction(allLines, idx7 + 1) : -1;
                           int idx9 = (idx8 != -1) ? findNextInstruction(allLines, idx8 + 1) : -1;
                           int idx10 = (idx9 != -1) ? findNextInstruction(allLines, idx9 + 1) : -1;
                           if(idx10 != -1) {
                              AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;
                              AsmInstruction inst5 = (AsmInstruction) allLines.get(idx5).line;
                              AsmInstruction inst6 = (AsmInstruction) allLines.get(idx6).line;
                              AsmInstruction inst7 = (AsmInstruction) allLines.get(idx7).line;
                              AsmInstruction inst8 = (AsmInstruction) allLines.get(idx8).line;
                              AsmInstruction inst9 = (AsmInstruction) allLines.get(idx9).line;
                              AsmInstruction inst10 = (AsmInstruction) allLines.get(idx10).line;

                              if(scopeAliasMap == null) scopeAliasMap = buildScopeAliasMap(allLines);
                              Map<String, String> aliases = scopeAliasMap.get(currentScope);
                              if(inst4.getCpuOpcode() != null && "lda".equals(inst4.getCpuOpcode().getMnemonic()) &&
                                 inst5.getCpuOpcode() != null && "ror".equals(inst5.getCpuOpcode().getMnemonic()) &&
                                 inst5.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON) &&
                                 inst6.getCpuOpcode() != null && "sta".equals(inst6.getCpuOpcode().getMnemonic()) &&
                                 isSameLocation(inst6.getOperand1(), opT, aliases) &&
                                 inst7.getCpuOpcode() != null && "lsr".equals(inst7.getCpuOpcode().getMnemonic()) &&
                                 isSameLocation(inst7.getOperand1(), opT1, aliases) &&
                                 inst8.getCpuOpcode() != null && "ror".equals(inst8.getCpuOpcode().getMnemonic()) &&
                                 isSameLocation(inst8.getOperand1(), opT, aliases) &&
                                 inst9.getCpuOpcode() != null && "lda".equals(inst9.getCpuOpcode().getMnemonic()) &&
                                 isSameLocation(inst9.getOperand1(), opT, aliases) &&
                                 inst10.getCpuOpcode() != null && "sta".equals(inst10.getCpuOpcode().getMnemonic()) &&
                                 !isVolatileOrHardwareOperand(opT, currentScope, resolver, getProgram())) {

                                 getLog().append("Folding 16-to-8 bit right-shift by 2: " + inst10.getOperand1());
                                 CpuOpcode lsrAcc = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lsr", CpuAddressingMode.NON, false);
                                 inst7.setCpuOpcode(lsrAcc);
                                 inst7.setOperand1(null);
                                 inst8.setCpuOpcode(inst10.getCpuOpcode());
                                 inst8.setOperand1(inst10.getOperand1());

                                 tombstoneLine(allLines, idx3);
                                 tombstoneLine(allLines, idx6);
                                 tombstoneLine(allLines, idx9);
                                 tombstoneLine(allLines, idx10);
                                 markScopeDirty(currentScope);

                                 passModified = true;
                                 overallModified = true;
                                 continue;
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
         if(!passModified && eliminateRedundantClc(allLines)) {
            passModified = true;
            overallModified = true;
         }
      }

      return overallModified;
   }

   /**
    * OPT-03: Static Carry Inference (CLC elimination when C=0 is guaranteed).
    * Tracks intra-block carry state and conservative unsigned upper bound on A.
    */
   private boolean eliminateRedundantClc(List<LineRef> allLines) {
      boolean modified = false;
      boolean carryClear = false;
      int maxA = 255;

      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(!isScopeDirty(ref.scope)) {
            carryClear = false;
            maxA = 255;
            continue;
         }
         AsmLine line = ref.line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
            continue;
         }
         if(!(line instanceof AsmInstruction inst) || inst.getCpuOpcode() == null) {
            carryClear = false;
            maxA = 255;
            continue;
         }

         CpuOpcode op = inst.getCpuOpcode();
         String mnem = op.getMnemonic();
         CpuAddressingMode mode = op.getAddressingMode();
         if(op.isJump() || mnem.startsWith("b") || mnem.equals("jsr") || mnem.equals("rts")
               || mnem.equals("rti") || mnem.equals("brk") || mnem.equals("sec")) {
            carryClear = false;
            maxA = 255;
            continue;
         }

         if(mnem.equals("clc")) {
            if(carryClear && !inst.isNoOptimize()) {
               getLog().append("OPT-03: Eliminating redundant clc (C=0 guaranteed)");
               tombstoneLine(allLines, i);
               markScopeDirty(ref.scope);
               modified = true;
               continue;
            }
            carryClear = true;
            continue;
         }

         if(mnem.equals("lda")) {
            Integer imm = mode.equals(CpuAddressingMode.IMM) ? parseByteImmediate(inst.getOperand1()) : null;
            maxA = (imm != null) ? imm : 255;
            continue;
         }
         if(mnem.equals("and")) {
            Integer imm = mode.equals(CpuAddressingMode.IMM) ? parseByteImmediate(inst.getOperand1()) : null;
            maxA = (imm != null) ? Math.min(maxA, imm) : 255;
            continue;
         }
         if(mnem.equals("lsr") && mode.equals(CpuAddressingMode.NON)) {
            carryClear = false;
            maxA = maxA >> 1;
            continue;
         }
         if(mnem.equals("asl") && mode.equals(CpuAddressingMode.NON)) {
            if(maxA <= 127) {
               carryClear = true;
               maxA = (maxA << 1) & 0xff;
            } else {
               carryClear = false;
               maxA = 255;
            }
            continue;
         }
         if(mnem.equals("adc")) {
            Integer imm = mode.equals(CpuAddressingMode.IMM) ? parseByteImmediate(inst.getOperand1()) : null;
            if(carryClear && imm != null && maxA + imm <= 255) {
               maxA = maxA + imm;
            } else {
               carryClear = false;
               maxA = 255;
            }
            continue;
         }
         if(mnem.equals("sbc") || mnem.equals("cmp") || mnem.equals("cpx") || mnem.equals("cpy")) {
            carryClear = false;
            if(mnem.equals("sbc")) maxA = 255;
            continue;
         }
         if(mnem.equals("ora") || mnem.equals("eor") || mnem.equals("txa") || mnem.equals("tya") || mnem.equals("pla")) {
            maxA = 255;
            continue;
         }
         if(op.getClobber().isFlagC()) {
            carryClear = false;
         }
         if(op.getClobber().isRegisterA()) {
            maxA = 255;
         }
      }
      return modified;
   }

   private static Integer parseByteImmediate(String op) {
      if(op == null) return null;
      String s = op.trim();
      if(s.startsWith("#")) s = s.substring(1).trim();
      try {
         int v;
         if(s.startsWith("$")) v = Integer.parseInt(s.substring(1), 16);
         else if(s.startsWith("%")) v = Integer.parseInt(s.substring(1), 2);
         else v = Integer.parseInt(s);
         if(v >= 0 && v <= 255) return v;
      } catch(NumberFormatException ignored) {
      }
      return null;
   }

   private Map<String, Map<String, String>> buildScopeAliasMap(List<LineRef> allLines) {
      Map<String, Map<String, String>> scopeAliasMap = new HashMap<>();
      for(LineRef ref : allLines) {
         if(ref.line instanceof AsmLabelDecl decl && isScopeDirty(ref.scope)) {
            scopeAliasMap.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(decl.getName(), decl.getValue());
         }
      }
      return scopeAliasMap;
   }

   private static boolean isSameLocation(String op1, String op2, Map<String, String> scopeAliases) {
      if(op1 == null || op2 == null) return false;
      if(op1.equals(op2)) return true;
      if(scopeAliases != null) {
         String r1 = scopeAliases.getOrDefault(op1, op1);
         String r2 = scopeAliases.getOrDefault(op2, op2);
         return r1.equals(r2);
      }
      return false;
   }
}
