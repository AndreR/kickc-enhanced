package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembler Peephole Optimization for stable NMOS 6510 Unintended (Illegal) Opcodes
 * and redundant comparisons.
 * Combines pairs of standard instructions into single stable illegal opcodes
 * (e.g. LDA/TAX -> LAX, AND #imm; LSR -> ALR, DEC/CMP -> DCP, INC/SBC -> ISC, etc.)
 * and eliminates redundant zero comparisons (CMP/CPX/CPY #0 before BEQ/BNE/BMI/BPL).
 * Operates across adjacent statement chunks within the same basic block.
 * Only executes when the target CPU supports MOS6502_UNODC (e.g. mos6502x, rom6502x).
 */
public class Pass5IllegalOpcodeOptimization extends Pass5AsmOptimization {



   public Pass5IllegalOpcodeOptimization(Program program) {
      super(program);
   }

   @Override
   public boolean optimize() {
      // Only execute if target CPU supports undocumented/illegal opcodes
      if(!getAsmProgram().getTargetCpu().getFeatures().contains(TargetCpu.Feature.MOS6502_UNODC)) {
         return false;
      }

      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         // Build a flat sequential list of all lines across all chunks
         List<LineRef> allLines = new ArrayList<>();
         Map<String, Map<String, String>> scopeAliasMap = new HashMap<>();
         String scanScope = "";
         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               allLines.add(new LineRef(chunk, line));
               if(line instanceof AsmScopeBegin) {
                  scanScope = ((AsmScopeBegin) line).getLabel();
               } else if(line instanceof AsmScopeEnd) {
                  scanScope = "";
               } else if(line instanceof AsmLabelDecl) {
                  AsmLabelDecl decl = (AsmLabelDecl) line;
                  scopeAliasMap.computeIfAbsent(scanScope, k -> new HashMap<>()).put(decl.getName(), decl.getValue());
               }
            }
         }
         String currentScope = "";

         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(ref1.line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) ref1.line).getLabel();
            } else if(ref1.line instanceof AsmScopeEnd) {
               currentScope = "";
            }
            if(!(ref1.line instanceof AsmInstruction)) {
               continue;
            }
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(inst1.isNoOptimize()) {
               continue;
            }

            // Find next executable instruction across chunks (skipping comments and harmless metadata)
            int nextIdx = -1;
            LineRef ref2 = null;
            for(int j = i + 1; j < allLines.size(); j++) {
               LineRef candidate = allLines.get(j);
               if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate.line instanceof AsmInstruction) {
                  nextIdx = j;
                  ref2 = candidate;
                  break;
               }
               // Encountered label, scope change, directive, or data - cannot optimize across boundaries
               break;
            }

            if(ref2 == null) {
               continue;
            }
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            if(inst2.isNoOptimize()) {
               continue;
            }

            // Find next executable instruction after inst2 (for branch lookahead)
            int branchIdx = -1;
            LineRef ref3 = null;
            for(int k = nextIdx + 1; k < allLines.size(); k++) {
               LineRef candidate = allLines.get(k);
               if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
                  continue;
               }
               if(candidate.line instanceof AsmInstruction) {
                  branchIdx = k;
                  ref3 = candidate;
                  break;
               }
               break;
            }
            AsmInstruction inst3 = ref3 != null ? (AsmInstruction) ref3.line : null;

            CpuOpcode op1 = inst1.getCpuOpcode();
            CpuOpcode op2 = inst2.getCpuOpcode();
            String mnem1 = op1.getMnemonic();
            String mnem2 = op2.getMnemonic();
            CpuAddressingMode mode1 = op1.getAddressingMode();
            CpuAddressingMode mode2 = op2.getAddressingMode();

            // -------------------------------------------------------------
            // Pattern 0a: Unsigned CMP #1 / CPX #1 / CPY #1 before BCC / BCS
            // On unsigned numbers: (x < 1) <==> (x == 0) and (x >= 1) <==> (x != 0).
            // CMP #1; BCC -> CMP #0; BEQ (and CMP #0 eliminated if previous inst sets Z)
            // CMP #1; BCS -> CMP #0; BNE (and CMP #0 eliminated if previous inst sets Z)
            // -------------------------------------------------------------
            if(inst3 != null && !inst3.isNoOptimize() && mode2.equals(CpuAddressingMode.IMM) && isOneOperand(inst2.getOperand1())) {
               String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
               if(mnem3.equals("bcc") || mnem3.equals("bcs")) {
                  // Ensure no other branch immediately follows inst3 on the fallthrough path
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
                     boolean canEliminateCmp = false;
                     if(mnem2.equals("cmp")) {
                        if(mnem1.equals("lda") || mnem1.equals("pla") ||
                           mnem1.equals("tax") || mnem1.equals("txa") ||
                           mnem1.equals("tay") || mnem1.equals("tya") ||
                           mnem1.equals("and") || mnem1.equals("ora") || mnem1.equals("eor") ||
                           mnem1.equals("lax") || mnem1.equals("alr") || mnem1.equals("anc") ||
                           ((mnem1.equals("asl") || mnem1.equals("lsr") || mnem1.equals("rol") || mnem1.equals("ror")) && mode1.equals(CpuAddressingMode.NON))) {
                           canEliminateCmp = true;
                        }
                     } else if(mnem2.equals("cpx")) {
                        if(mnem1.equals("ldx") || mnem1.equals("inx") || mnem1.equals("dex") ||
                           mnem1.equals("tax") || mnem1.equals("tsx") || mnem1.equals("lax") || mnem1.equals("axs")) {
                           canEliminateCmp = true;
                        }
                     } else if(mnem2.equals("cpy")) {
                        if(mnem1.equals("ldy") || mnem1.equals("iny") || mnem1.equals("dey") || mnem1.equals("tay")) {
                           canEliminateCmp = true;
                        }
                     }

                     if(canEliminateCmp) {
                        getLog().append("Eliminating redundant " + inst2.getAsm() + " and converting " + mnem3 + " to " + newBranchMnem);
                        inst3.setCpuOpcode(newBranchOp);
                        ref2.chunk.getLines().remove(inst2);
                        passModified = true;
                        overallModified = true;
                        break;
                     } else {
                        getLog().append("Converting " + inst2.getAsm() + "; " + mnem3 + " to CMP #0; " + newBranchMnem);
                        inst2.setOperand1("0");
                        inst3.setCpuOpcode(newBranchOp);
                        passModified = true;
                        overallModified = true;
                        break;
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
               int i2 = findNextInstruction(allLines, i + 1);
               int i3 = i2 != -1 ? findNextInstruction(allLines, i2 + 1) : -1;
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
                        isSameLocation(var1, varC, aliases)) {

                        getLog().append("Folding 8-to-16 cast and << 8 on " + varC);
                        inst1.setOperand1(instD.getOperand1());
                        instB.setOperand1(instF.getOperand1());
                        allLines.get(i4).chunk.getLines().remove(instC);
                        allLines.get(i5).chunk.getLines().remove(instD);
                        allLines.get(i6).chunk.getLines().remove(instE);
                        allLines.get(i7).chunk.getLines().remove(instF);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0f: Register shuffle cascade
            // tay; txa; sty.z ZP; ldx.z ZP; tay; txa; sty.z TARGET
            // -> stx.z TARGET (when ZP is scratch $fe/$ff)
            // -------------------------------------------------------------
            if(mnem1.equals("tay")) {
               int i2 = findNextInstruction(allLines, i + 1);
               int i3 = i2 != -1 ? findNextInstruction(allLines, i2 + 1) : -1;
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
                     if(opScratch != null && opScratch.equals(instC.getOperand1())) {
                        String targetOp = instF.getOperand1();
                        CpuAddressingMode targetMode = instF.getCpuOpcode().getAddressingMode();
                        CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", targetMode, false);
                        if(stxOp != null) {
                           getLog().append("Collapsing register shuffle cascade to STX " + targetOp);
                           inst1.setCpuOpcode(stxOp);
                           inst1.setOperand1(targetOp);
                           allLines.get(i2).chunk.getLines().remove(instA);
                           allLines.get(i3).chunk.getLines().remove(instB);
                           allLines.get(i4).chunk.getLines().remove(instC);
                           allLines.get(i5).chunk.getLines().remove(instD);
                           allLines.get(i6).chunk.getLines().remove(instE);
                           allLines.get(i7).chunk.getLines().remove(instF);
                           passModified = true;
                           overallModified = true;
                           break;
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
               int i2 = findNextInstruction(allLines, i + 1);
               int i3 = i2 != -1 ? findNextInstruction(allLines, i2 + 1) : -1;
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

                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     String base1 = getBaseVar(inst1.getOperand1());
                     String baseA = getBaseVar(instA.getOperand1());
                     String baseC = getBaseVar(instC.getOperand1());
                     String baseD = getBaseVar(instD.getOperand1());

                     if(getOffset(instA.getOperand1()) == 0 && getOffset(instC.getOperand1()) == 1 && getOffset(instD.getOperand1()) == 0 &&
                        isSameLocation(baseA, baseC, aliases) && isSameLocation(baseA, baseD, aliases)) {

                        if(instE.getCpuOpcode().getMnemonic().equals("rts")) {
                           getLog().append("Eliminating redundant right-shift intermediate stores before RTS");
                           allLines.get(i2).chunk.getLines().remove(instA);
                           allLines.get(i3).chunk.getLines().remove(instB);
                           allLines.get(i4).chunk.getLines().remove(instC);
                           allLines.get(i5).chunk.getLines().remove(instD);
                           passModified = true;
                           overallModified = true;
                           break;
                        } else if(instE.getCpuOpcode().getMnemonic().equals("sta")) {
                           getLog().append("Forwarding right-shift high-byte load directly to STA " + instE.getOperand1());
                           allLines.get(i2).chunk.getLines().remove(instA);
                           allLines.get(i3).chunk.getLines().remove(instB);
                           allLines.get(i4).chunk.getLines().remove(instC);
                           allLines.get(i5).chunk.getLines().remove(instD);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0h: Elimination of intermediate $fd store/reload
            // ldy <IDX>; sty.z $fd; ldy <PTR>; sty.z $fe; ldy <PTR>+1; sty.z $ff; ldy.z $fd; (sta|lda) ($fe),y
            // -> ldy <PTR>; sty.z $fe; ldy <PTR>+1; sty.z $ff; ldy <IDX>; (sta|lda) ($fe),y
            // Eliminates $fd register usage, saves 4 bytes and 6-7 CPU cycles.
            // -------------------------------------------------------------
            if(mnem1.equals("ldy")) {
               int i2 = findNextInstruction(allLines, i + 1);
               int i3 = i2 != -1 ? findNextInstruction(allLines, i2 + 1) : -1;
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

                  if(instA.getCpuOpcode() != null && instA.getCpuOpcode().getMnemonic().equals("sty") && isFdOperand(instA.getOperand1()) &&
                     instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("ldy") &&
                     instC.getCpuOpcode() != null && instC.getCpuOpcode().getMnemonic().equals("sty") && isFeOperand(instC.getOperand1()) &&
                     instD.getCpuOpcode() != null && instD.getCpuOpcode().getMnemonic().equals("ldy") &&
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("sty") && isFfOperand(instE.getOperand1()) &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("ldy") && isFdOperand(instF.getOperand1()) &&
                     instG.getCpuOpcode() != null && (instG.getCpuOpcode().getMnemonic().equals("sta") || instG.getCpuOpcode().getMnemonic().equals("lda")) &&
                     instG.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IZY)) {

                     String idxOp = inst1.getOperand1();
                     CpuAddressingMode idxMode = inst1.getCpuOpcode().getAddressingMode();
                     if(!isFeOperand(idxOp) && !isFfOperand(idxOp) && !isFdOperand(idxOp)) {
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
                        allLines.get(i6).chunk.getLines().remove(instE);
                        allLines.get(i7).chunk.getLines().remove(instF);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0i: 8-to-16 cast followed by << 6 on same variable
            // sta X; lda #0; sta X+1; lda Y+1; lsr; sta $ff; lda Y; ror; sta Y+1; lda #0; ror; sta Y; lsr $ff; ror Y+1; ror Y
            // (where X == Y)
            // -> sta Y+1; lda #0; lsr Y+1; ror; lsr Y+1; ror; sta Y
            // -------------------------------------------------------------
            if(mnem1.equals("sta") && getOffset(inst1.getOperand1()) == 0) {
               int i2 = findNextInstruction(allLines, i + 1);
               int i3 = i2 != -1 ? findNextInstruction(allLines, i2 + 1) : -1;
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
                     instE.getCpuOpcode() != null && instE.getCpuOpcode().getMnemonic().equals("sta") && isFfOperand(instE.getOperand1()) &&
                     instF.getCpuOpcode() != null && instF.getCpuOpcode().getMnemonic().equals("lda") && getOffset(instF.getOperand1()) == 0 &&
                     instG.getCpuOpcode() != null && instG.getCpuOpcode().getMnemonic().equals("ror") &&
                     instH.getCpuOpcode() != null && instH.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instH.getOperand1()) == 1 &&
                     instI.getCpuOpcode() != null && instI.getCpuOpcode().getMnemonic().equals("lda") && isZeroOperand(instI.getOperand1()) &&
                     instJ.getCpuOpcode() != null && instJ.getCpuOpcode().getMnemonic().equals("ror") &&
                     instK.getCpuOpcode() != null && instK.getCpuOpcode().getMnemonic().equals("sta") && getOffset(instK.getOperand1()) == 0 &&
                     instL.getCpuOpcode() != null && instL.getCpuOpcode().getMnemonic().equals("lsr") && isFfOperand(instL.getOperand1()) &&
                     instM.getCpuOpcode() != null && instM.getCpuOpcode().getMnemonic().equals("ror") && getOffset(instM.getOperand1()) == 1 &&
                     instN.getCpuOpcode() != null && instN.getCpuOpcode().getMnemonic().equals("ror") && getOffset(instN.getOperand1()) == 0) {

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
                        isSameLocation(var1, varN, aliases)) {

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

                        allLines.get(i8).chunk.getLines().remove(instG);
                        allLines.get(i9).chunk.getLines().remove(instH);
                        allLines.get(i10).chunk.getLines().remove(instI);
                        allLines.get(i11).chunk.getLines().remove(instJ);
                        allLines.get(i12).chunk.getLines().remove(instK);
                        allLines.get(i13).chunk.getLines().remove(instL);
                        allLines.get(i14).chunk.getLines().remove(instM);
                        allLines.get(i15).chunk.getLines().remove(instN);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0j: Redundant TXA after LAX
            // LAX loads identical value into both A and X.
            // If TXA follows directly or after non-destructive STA/STX, TXA is a pure no-op.
            // -------------------------------------------------------------
            if(mnem1.equals("lax")) {
               int i2 = findNextInstruction(allLines, i + 1);
               if(i2 != -1) {
                  AsmInstruction instA = (AsmInstruction) allLines.get(i2).line;
                  if(instA.getCpuOpcode() != null) {
                     String mnemA = instA.getCpuOpcode().getMnemonic();
                     if(mnemA.equals("txa")) {
                        getLog().append("Eliminating redundant TXA after LAX");
                        allLines.get(i2).chunk.getLines().remove(instA);
                        passModified = true;
                        overallModified = true;
                        break;
                     } else if(mnemA.equals("sta") || mnemA.equals("stx")) {
                        int i3 = findNextInstruction(allLines, i2 + 1);
                        if(i3 != -1) {
                           AsmInstruction instB = (AsmInstruction) allLines.get(i3).line;
                           if(instB.getCpuOpcode() != null && instB.getCpuOpcode().getMnemonic().equals("txa")) {
                              getLog().append("Eliminating redundant TXA after LAX and store");
                              allLines.get(i3).chunk.getLines().remove(instB);
                              passModified = true;
                              overallModified = true;
                              break;
                           }
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0k: Redundant TXA elimination
            // If A already contains the value of X and neither A nor X has been modified
            // (across STA/STX/STY/LDY/etc.), any subsequent TXA is completely redundant.
            // -------------------------------------------------------------
            if(mnem1.equals("txa")) {
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
                        cand.chunk.getLines().remove(instK);
                        passModified = true;
                        overallModified = true;
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
               if(passModified) break;
            }

            // -------------------------------------------------------------
            // Pattern 0l: 16-bit transitive copy chain fold
            // lda SRC; sta D1; lda SRC+1; sta D1+1; lda D1; sta D2; lda D1+1; sta D2+1
            // -> lda SRC; sta D1; sta D2; lda SRC+1; sta D1+1; sta D2+1
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && getOffset(inst1.getOperand1()) == 0) {
               int i2 = findNextInstruction(allLines, i + 1);
               int i3 = i2 != -1 ? findNextInstruction(allLines, i2 + 1) : -1;
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
                        !isSameLocation(baseSrcLo, baseD2Lo, aliases)) {

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

                         allLines.get(i5).chunk.getLines().remove(instD);
                         allLines.get(i6).chunk.getLines().remove(instE);
                         allLines.get(i7).chunk.getLines().remove(instF);
                         allLines.get(i8).chunk.getLines().remove(instG);
                         passModified = true;
                         overallModified = true;
                         break;
                      }
                   }
                }
             }

            // -------------------------------------------------------------
            // Pattern 0: Redundant CMP #0 / CPX #0 / CPY #0 elimination
            // When inst1 is CMP/CPX/CPY #0 and inst2 is BEQ/BNE/BMI/BPL,
            // and flags are already established on that register (immediately before,
            // across non-flag-modifying stores, or across all block predecessors),
            // inst1 is 100% redundant and can be safely removed.
            // -------------------------------------------------------------
            if(mode1.equals(CpuAddressingMode.IMM) && isZeroOperand(inst1.getOperand1())) {
               String reg = mnem1.equals("cmp") ? "A" : (mnem1.equals("cpx") ? "X" : (mnem1.equals("cpy") ? "Y" : null));
               if(reg != null && inst2 != null && !inst2.isNoOptimize() && inst2.getCpuOpcode() != null) {
                  String m2 = inst2.getCpuOpcode().getMnemonic();
                  boolean isZOrNBranch = m2.equals("beq") || m2.equals("bne") || m2.equals("bmi") || m2.equals("bpl");
                  if(isZOrNBranch && areFlagsEstablishedForReg(allLines, i, reg, currentScope)) {
                     getLog().append("Eliminating redundant zero comparison " + inst1.getAsm() + " before " + inst2.getAsm());
                     ref1.chunk.getLines().remove(inst1);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0b: Redundant LDA <mem> after DEC <mem> / INC <mem>
            // DEC/INC already sets Z and N flags. If followed by a Z/N branch (BEQ/BNE/BMI/BPL)
            // and A is dead along both the fallthrough path and the target branch path,
            // LDA is completely redundant and can be safely eliminated.
            // -------------------------------------------------------------
            if(inst3 != null && !inst3.isNoOptimize() && branchIdx != -1 &&
               (mnem1.equals("dec") || mnem1.equals("inc")) && mnem2.equals("lda") &&
               inst1.getOperand1() != null && inst1.getOperand1().equals(inst2.getOperand1()) && mode1.equals(mode2)) {
               String mnem3 = inst3.getCpuOpcode() != null ? inst3.getCpuOpcode().getMnemonic() : "";
               boolean isZOrNBranch = mnem3.equals("beq") || mnem3.equals("bne") || mnem3.equals("bmi") || mnem3.equals("bpl");
               if(isZOrNBranch) {
                  int targetIdx = findLabelIndex(allLines, inst3.getOperand1(), branchIdx);
                  boolean targetDead = targetIdx != -1 && isADeadAlongPath(allLines, targetIdx + 1, new HashSet<>(), currentScope, getProgram());
                  boolean fallthroughDead = isADeadAlongPath(allLines, branchIdx + 1, new HashSet<>(), currentScope, getProgram());
                  if(targetDead && fallthroughDead) {
                     getLog().append("Eliminating redundant load " + inst2.getAsm() + " after " + inst1.getAsm() + " before " + inst3.getAsm());
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 0c: Redundant EOR #0
            // EOR #0 leaves A unchanged. If previous instruction already set
            // Z and N flags (e.g. LDA, PLA, arithmetic, logic), EOR #0 is a pure no-op.
            // -------------------------------------------------------------
            if(mnem2.equals("eor") && mode2.equals(CpuAddressingMode.IMM) && isZeroOperand(inst2.getOperand1())) {
               if(mnem1.equals("lda") || mnem1.equals("pla") || mnem1.equals("txa") || mnem1.equals("tya") ||
                  mnem1.equals("and") || mnem1.equals("ora") || mnem1.equals("adc") || mnem1.equals("sbc") ||
                  mnem1.equals("asl") || mnem1.equals("lsr") || mnem1.equals("rol") || mnem1.equals("ror") ||
                  mnem1.equals("lax") || mnem1.equals("alr")) {
                  getLog().append("Eliminating redundant " + inst2.getAsm() + " after " + inst1.getAsm());
                  ref2.chunk.getLines().remove(inst2);
                  passModified = true;
                  overallModified = true;
                  break;
               }
            }

            // -------------------------------------------------------------
            // Pattern 0d: Redundant roundtrip transfers TAX; TXA, TXA; TAX, TAY; TYA, TYA; TAY
            // Second transfer immediately after first leaves both registers and Z/N flags unchanged.
            // -------------------------------------------------------------
            if(((mnem1.equals("tax") && mnem2.equals("txa")) ||
                (mnem1.equals("txa") && mnem2.equals("tax")) ||
                (mnem1.equals("tay") && mnem2.equals("tya")) ||
                (mnem1.equals("tya") && mnem2.equals("tay"))) &&
               mode1.equals(CpuAddressingMode.NON) && mode2.equals(CpuAddressingMode.NON)) {
               getLog().append("Eliminating redundant transfer " + inst2.getAsm() + " after " + inst1.getAsm());
               ref2.chunk.getLines().remove(inst2);
               passModified = true;
               overallModified = true;
               break;
            }

            // -------------------------------------------------------------
            // Pattern 0e: Dead Overwritten Load
            // If inst1 is LDA/LDX/LDY (non-hardware IMM/ZP/ABS) and inst2 immediately
            // overwrites that register without reading flags or register, inst1 is dead.
            // -------------------------------------------------------------
            boolean isDeadOverwrittenLoad = false;
            if(!inst1.isNoOptimize() && !inst2.isNoOptimize() &&
               (mode1.equals(CpuAddressingMode.IMM) || mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
               !Pass5RedundantStoreAndReloadElimination.isHardwareAddress(inst1.getOperand1())) {
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
               ref1.chunk.getLines().remove(inst1);
               passModified = true;
               overallModified = true;
               break;
            }

            // -------------------------------------------------------------
            // Pattern 0f: 16-bit Zero-Extension before Left Shift
            // sta V; lda #0; sta V+1; asl W; rol W+1
            // ->
            // asl; sta V; lda #0; rol; sta V+1
            // -------------------------------------------------------------
            if(mnem1.equals("sta") && (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS))) {
               String opV = inst1.getOperand1();
               int idx2 = findNextInstruction(allLines, i + 1);
               if(idx2 != -1) {
                  LineRef r2 = allLines.get(idx2);
                  AsmInstruction in2 = (AsmInstruction) r2.line;
                  if(in2.getCpuOpcode() != null && "lda".equals(in2.getCpuOpcode().getMnemonic()) &&
                     AsmProgramStaticRegisterValues.matchImm(in2.getOperand1(), "0")) {
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
                                       r3.chunk.getLines().remove(in3);
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
            }

            // -------------------------------------------------------------
            // Pattern 0g: 16-to-8 Bit Right Shift by 2 folding
            // lda HI; lsr; sta T+1; lda LO; ror; sta T; lsr T+1; ror T; lda T; sta DEST
            // ->
            // lda HI; lsr; lda LO; ror; lsr; sta DEST
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS))) {
               int idx2 = findNextInstruction(allLines, i + 1);
               if(idx2 != -1) {
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

                                 if(inst4.getCpuOpcode() != null && "lda".equals(inst4.getCpuOpcode().getMnemonic()) &&
                                    inst5.getCpuOpcode() != null && "ror".equals(inst5.getCpuOpcode().getMnemonic()) &&
                                    inst5.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON) &&
                                    inst6.getCpuOpcode() != null && "sta".equals(inst6.getCpuOpcode().getMnemonic()) &&
                                    isSameLocation(inst6.getOperand1(), opT, scopeAliasMap.get(currentScope)) &&
                                    inst7.getCpuOpcode() != null && "lsr".equals(inst7.getCpuOpcode().getMnemonic()) &&
                                    isSameLocation(inst7.getOperand1(), opT1, scopeAliasMap.get(currentScope)) &&
                                    inst8.getCpuOpcode() != null && "ror".equals(inst8.getCpuOpcode().getMnemonic()) &&
                                    isSameLocation(inst8.getOperand1(), opT, scopeAliasMap.get(currentScope)) &&
                                    inst9.getCpuOpcode() != null && "lda".equals(inst9.getCpuOpcode().getMnemonic()) &&
                                    isSameLocation(inst9.getOperand1(), opT, scopeAliasMap.get(currentScope)) &&
                                    inst10.getCpuOpcode() != null && "sta".equals(inst10.getCpuOpcode().getMnemonic())) {

                                    getLog().append("Folding 16-to-8 bit right-shift by 2: " + inst10.getOperand1());
                                    CpuOpcode lsrAcc = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lsr", CpuAddressingMode.NON, false);
                                    inst7.setCpuOpcode(lsrAcc);
                                    inst7.setOperand1(null);
                                    inst8.setCpuOpcode(inst10.getCpuOpcode());
                                    inst8.setOperand1(inst10.getOperand1());

                                    allLines.get(idx3).chunk.getLines().remove(in3);
                                    allLines.get(idx6).chunk.getLines().remove(inst6);
                                    allLines.get(idx9).chunk.getLines().remove(inst9);
                                    allLines.get(idx10).chunk.getLines().remove(inst10);

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
            // Pattern 1: LDA <mem>; TAX -> LAX <mem>
            // Stable addressing modes: ZP, ABS, IZY, ABY, ZPY, IZX
            // (IMM is strictly excluded because LAX #imm is analog/unstable!)
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.equals("tax") && mode2.equals(CpuAddressingMode.NON)) {
               if(mode1.equals(CpuAddressingMode.ZP) ||
                  mode1.equals(CpuAddressingMode.ABS) ||
                  mode1.equals(CpuAddressingMode.IZY) ||
                  mode1.equals(CpuAddressingMode.ABY) ||
                  mode1.equals(CpuAddressingMode.ZPY) ||
                  mode1.equals(CpuAddressingMode.IZX)) {
                  CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                  if(laxOpcode != null) {
                     getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with LAX");
                     inst1.setCpuOpcode(laxOpcode);
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1b: LDX <mem>; TXA -> LAX <mem>
            // Stable addressing modes: ZP, ABS, ZPY, ABY, IZX, IZY
            // -------------------------------------------------------------
            if(mnem1.equals("ldx") && mnem2.equals("txa") && mode2.equals(CpuAddressingMode.NON)) {
               if(mode1.equals(CpuAddressingMode.ZP) ||
                  mode1.equals(CpuAddressingMode.ABS) ||
                  mode1.equals(CpuAddressingMode.ZPY) ||
                  mode1.equals(CpuAddressingMode.ABY) ||
                  mode1.equals(CpuAddressingMode.IZX) ||
                  mode1.equals(CpuAddressingMode.IZY)) {
                  CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                  if(laxOpcode != null) {
                     getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with LAX");
                     inst1.setCpuOpcode(laxOpcode);
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1c: LDA <mem>; STA <dst>; TAX -> LAX <mem>; STA <dst>
            // Stable addressing modes: ZP, ABS, IZY, ABY, ZPY, IZX
            // STA modifies neither A nor X nor flags.
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.equals("sta") &&
               (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS) ||
                mode1.equals(CpuAddressingMode.IZY) || mode1.equals(CpuAddressingMode.ABY) ||
                mode1.equals(CpuAddressingMode.ZPY) || mode1.equals(CpuAddressingMode.IZX))) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                  if(instNext3.getCpuOpcode() != null && instNext3.getCpuOpcode().getMnemonic().equals("tax") &&
                     instNext3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     Map<String, String> aliases = scopeAliasMap.get(currentScope);
                     if(!isSameLocation(inst1.getOperand1(), inst2.getOperand1(), aliases)) {
                        CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                        if(laxOpcode != null) {
                           getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + "; TAX with LAX");
                           inst1.setCpuOpcode(laxOpcode);
                           allLines.get(idx3).chunk.getLines().remove(instNext3);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1d: LDA <mem>; Bxx <target>; TAX -> LAX <mem>; Bxx <target>
            // When A is loaded, tested by conditional branch, and the fallthrough
            // immediately moves A to X via TAX. Safe when X is dead along branch target.
            // -------------------------------------------------------------
            if(mnem1.equals("lda") && mnem2.startsWith("b") &&
               (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS) ||
                mode1.equals(CpuAddressingMode.IZY) || mode1.equals(CpuAddressingMode.ABY) ||
                mode1.equals(CpuAddressingMode.ZPY) || mode1.equals(CpuAddressingMode.IZX))) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                  if(instNext3.getCpuOpcode() != null && instNext3.getCpuOpcode().getMnemonic().equals("tax") &&
                     instNext3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     String targetLabel = inst2.getOperand1();
                     int targetIdx = findLabelIndex(allLines, targetLabel, nextIdx);
                     if(targetIdx != -1 && isXDeadAlongPath(allLines, targetIdx + 1, new HashSet<>(), currentScope, getProgram())) {
                        CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                        if(laxOpcode != null) {
                           getLog().append("Lifting TAX into LAX before branch: " + inst1.getAsm() + "; " + inst2.getAsm() + "; TAX");
                           inst1.setCpuOpcode(laxOpcode);
                           allLines.get(idx3).chunk.getLines().remove(instNext3);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 1e: LDX <mem>; LDY <mem> -> LAX <mem>; TAY
            // -------------------------------------------------------------
            if(mnem1.equals("ldx") && mnem2.equals("ldy") &&
               inst1.getOperand1() != null && inst1.getOperand1().equals(inst2.getOperand1()) &&
               (mode1.equals(CpuAddressingMode.ZP) || mode1.equals(CpuAddressingMode.ABS)) &&
               mode1.equals(mode2)) {
               if(isADeadAlongPath(allLines, i, new HashSet<>(), currentScope, getProgram())) {
                  CpuOpcode laxOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lax", mode1, false);
                  CpuOpcode tayOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tay", CpuAddressingMode.NON, false);
                  if(laxOpcode != null && tayOpcode != null) {
                     getLog().append("Replacing LDX " + inst1.getOperand1() + "; LDY " + inst2.getOperand1() + " with LAX; TAY");
                     inst1.setCpuOpcode(laxOpcode);
                     inst2.setCpuOpcode(tayOpcode);
                     inst2.setOperand1(null);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 2: AND #{imm}; LSR -> ALR #{imm}
            // ALR executes A = (A & #{imm}) >> 1 and sets Carry to bit 0.
            // -------------------------------------------------------------
            if(mnem1.equals("and") && mode1.equals(CpuAddressingMode.IMM) &&
               mnem2.equals("lsr") && mode2.equals(CpuAddressingMode.NON)) {
               CpuOpcode alrOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("alr", CpuAddressingMode.IMM, false);
               if(alrOpcode != null) {
                  getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with ALR");
                  inst1.setCpuOpcode(alrOpcode);
                  ref2.chunk.getLines().remove(inst2);
                  passModified = true;
                  overallModified = true;
                  break;
               }
            }

            // -------------------------------------------------------------
            // Pattern 2b: LSR; AND #{imm} -> ALR #{(imm << 1)}
            // ALR executes A = (A & #{(imm << 1)}) >> 1. Equivalent to (A >> 1) & #{imm}.
            // Safe when bit 7 of #{imm} is 0 and subsequent instruction does not test Carry.
            // -------------------------------------------------------------
            if(mnem1.equals("lsr") && mode1.equals(CpuAddressingMode.NON) &&
               mnem2.equals("and") && mode2.equals(CpuAddressingMode.IMM)) {
               Integer maskVal = AsmProgramStaticRegisterValues.getImmValue(inst2.getOperand1());
               if(maskVal != null && (maskVal & 0x80) == 0 && maskVal >= 0 && maskVal <= 127) {
                  int idx3 = findNextInstruction(allLines, nextIdx + 1);
                  boolean safeCarry = true;
                  if(idx3 != -1) {
                     AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                     if(instNext3.getCpuOpcode() != null) {
                        String m3 = instNext3.getCpuOpcode().getMnemonic();
                        if(m3.equals("bcc") || m3.equals("bcs") || m3.equals("adc") || m3.equals("sbc") || m3.equals("rol") || m3.equals("ror")) {
                           safeCarry = false;
                        }
                     }
                  }
                  if(safeCarry) {
                     CpuOpcode alrOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("alr", CpuAddressingMode.IMM, false);
                     if(alrOpcode != null) {
                        int alrImm = (maskVal << 1) & 0xff;
                        getLog().append("Replacing LSR; " + inst2.getAsm() + " with ALR #$" + Integer.toHexString(alrImm));
                        inst1.setCpuOpcode(alrOpcode);
                        inst1.setOperand1("$" + Integer.toHexString(alrImm));
                        ref2.chunk.getLines().remove(inst2);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Pattern 2c: LSR; LSR; ASL -> ALR #$fc
            // ((A >> 2) << 1) clears bits 0 and 1, then shifts right by 1.
            // ALR #$fc computes (A & $fc) >> 1, identical result in 2 bytes / 2 cycles.
            // -------------------------------------------------------------
            if(mnem1.equals("lsr") && mode1.equals(CpuAddressingMode.NON) &&
               mnem2.equals("lsr") && mode2.equals(CpuAddressingMode.NON)) {
               int idx3 = findNextInstruction(allLines, nextIdx + 1);
               if(idx3 != -1) {
                  AsmInstruction instNext3 = (AsmInstruction) allLines.get(idx3).line;
                  if(instNext3.getCpuOpcode() != null && instNext3.getCpuOpcode().getMnemonic().equals("asl") &&
                     instNext3.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.NON)) {
                     CpuOpcode alrOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("alr", CpuAddressingMode.IMM, false);
                     if(alrOpcode != null) {
                        getLog().append("Replacing LSR; LSR; ASL with ALR #$fc");
                        inst1.setCpuOpcode(alrOpcode);
                        inst1.setOperand1("$fc");
                        ref2.chunk.getLines().remove(inst2);
                        allLines.get(idx3).chunk.getLines().remove(instNext3);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }

            // -------------------------------------------------------------
            // Helper for 2-instruction RMW sequences on the same memory operand:
            // inst1 <mem>; inst2 <mem> -> combined <mem>
            // -------------------------------------------------------------
            boolean sameOperand = inst1.getOperand1() != null &&
                                  inst1.getOperand1().equals(inst2.getOperand1()) &&
                                  mode1.equals(mode2);

            if(sameOperand) {
               String combinedMnemonic = null;

               // Pattern 3: DEC <mem>; CMP <mem> -> DCP <mem>
               if(mnem1.equals("dec") && mnem2.equals("cmp")) {
                  combinedMnemonic = "dcp";
               }
               // Pattern 4: INC <mem>; SBC <mem> -> ISC <mem>
               else if(mnem1.equals("inc") && mnem2.equals("sbc")) {
                  combinedMnemonic = "isc";
               }
               // Pattern 5: ASL <mem>; ORA <mem> -> SLO <mem>
               else if(mnem1.equals("asl") && mnem2.equals("ora")) {
                  combinedMnemonic = "slo";
               }
               // Pattern 6: ROL <mem>; AND <mem> -> RLA <mem>
               else if(mnem1.equals("rol") && mnem2.equals("and")) {
                  combinedMnemonic = "rla";
               }
               // Pattern 7: LSR <mem>; EOR <mem> -> SRE <mem>
               else if(mnem1.equals("lsr") && mnem2.equals("eor")) {
                  combinedMnemonic = "sre";
               }
               // Pattern 8: ROR <mem>; ADC <mem> -> RRA <mem>
               else if(mnem1.equals("ror") && mnem2.equals("adc")) {
                  combinedMnemonic = "rra";
               }

               if(combinedMnemonic != null) {
                  CpuOpcode combinedOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode(combinedMnemonic, mode1, false);
                  if(combinedOpcode != null) {
                     getLog().append("Replacing " + inst1.getAsm() + "; " + inst2.getAsm() + " with " + combinedMnemonic.toUpperCase());
                     inst1.setCpuOpcode(combinedOpcode);
                     ref2.chunk.getLines().remove(inst2);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }
         }
      }

      return overallModified;
   }

   /**
    * Checks if an ASM operand string evaluates to integer 1.
    */
   private static boolean isOneOperand(String op) {
      if(op == null) return false;
      String trimmed = op.trim();
      if(trimmed.contains(">") || trimmed.contains("<")) return false;
      Integer val = AsmProgramStaticRegisterValues.getImmValue(op);
      return val != null && val == 1;
   }

   private static int findNextInstruction(List<LineRef> allLines, int start) {
      for(int j = start; j < allLines.size(); j++) {
         LineRef candidate = allLines.get(j);
         if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
            continue;
         }
         if(candidate.line instanceof AsmInstruction) {
            return j;
         }
         break;
      }
      return -1;
   }

   private static String getBaseVar(String op) {
      if(op == null) return "";
      int idx = op.indexOf('+');
      if(idx != -1) return op.substring(0, idx).trim();
      return op.trim();
   }

   private static int getOffset(String op) {
      if(op == null) return 0;
      int idx = op.indexOf('+');
      if(idx != -1) {
         try {
            return Integer.parseInt(op.substring(idx + 1).trim());
         } catch(Exception e) {
            return -1;
         }
      }
      return 0;
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

   private static boolean isFdOperand(String op) {
      if(op == null) return false;
      String t = op.trim().toLowerCase();
      return t.equals("$fd") || t.equals("$$fd") || t.equals("$00fd");
   }

   private static boolean isFeOperand(String op) {
      if(op == null) return false;
      String t = op.trim().toLowerCase();
      return t.equals("$fe") || t.equals("$$fe") || t.equals("$00fe");
   }



   private static boolean isFfOperand(String op) {
      if(op == null) return false;
      String t = op.trim().toLowerCase();
      return t.equals("$ff") || t.equals("$$ff") || t.equals("$00ff");
   }

   private static boolean isZeroOperand(String op) {
      if(op == null) return false;
      String trimmed = op.trim();
      if(trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
      if(trimmed.startsWith("<") || trimmed.startsWith(">")) {
         trimmed = trimmed.substring(1).trim();
      }
      if(trimmed.equals("0") || trimmed.equals("$0") || trimmed.equals("$00") || trimmed.equals("0x0") || trimmed.equals("0x00")) {
         return true;
      }
      try {
         if(trimmed.startsWith("$")) {
            return Integer.parseInt(trimmed.substring(1), 16) == 0;
         } else if(trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16) == 0;
         } else {
            return Integer.parseInt(trimmed) == 0;
         }
      } catch(NumberFormatException e) {
         return false;
      }
   }

   private static int findLabelIndex(List<LineRef> allLines, String labelName, int fromIdx) {
      if(labelName == null) return -1;
      String clean = labelName.trim();
      if(clean.startsWith("!")) {
         boolean forward = clean.endsWith("+");
         String base = forward ? clean.substring(0, clean.length() - 1) : (clean.endsWith("-") ? clean.substring(0, clean.length() - 1) : clean);
         if(forward) {
            for(int i = fromIdx + 1; i < allLines.size(); i++) {
               LineRef ref = allLines.get(i);
               if(ref.line instanceof AsmLabel) {
                  String l = ((AsmLabel) ref.line).getLabel();
                  if(l != null && (l.equals(base) || (l + "+").equals(clean) || (base + "+").equals(l))) {
                     return i;
                  }
               }
            }
         } else {
            for(int i = fromIdx - 1; i >= 0; i--) {
               LineRef ref = allLines.get(i);
               if(ref.line instanceof AsmLabel) {
                  String l = ((AsmLabel) ref.line).getLabel();
                  if(l != null && (l.equals(base) || (l + "-").equals(clean))) {
                     return i;
                  }
               }
            }
         }
         return -1;
      }
      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(ref.line instanceof AsmLabel) {
            String l = ((AsmLabel) ref.line).getLabel();
            if(clean.equals(l)) return i;
         }
      }
      return -1;
   }

   private static boolean isADeadAlongPath(List<LineRef> allLines, int startIdx, Set<String> visitedLabels, String currentScope, dk.camelot64.kickc.model.Program program) {
      for(int m = startIdx; m < allLines.size(); m++) {
         LineRef cand = allLines.get(m);
         if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
         if(cand.line instanceof AsmLabel) continue;
         if(cand.line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) cand.line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) return false;
            String mnem = op.getMnemonic();
            CpuAddressingMode mode = op.getAddressingMode();
            if(mnem.equals("lda") || mnem.equals("pla") || mnem.equals("txa") || mnem.equals("tya") || mnem.equals("lax")) {
               return true;
            }
            if(mnem.equals("sta") || mnem.equals("tax") || mnem.equals("tay") || mnem.equals("pha") ||
               mnem.equals("cmp") || mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
               mnem.equals("ora") || mnem.equals("eor") || mnem.equals("sax") ||
               mnem.equals("alr") || mnem.equals("anc") || mnem.equals("arr") || mnem.equals("axs") ||
               mnem.equals("dcp") || mnem.equals("isc") || mnem.equals("rla") || mnem.equals("rra") ||
               mnem.equals("slo") || mnem.equals("sre") ||
               ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) && CpuAddressingMode.NON.equals(mode))) {
               return false;
            }
            if(mnem.equals("jsr")) {
               return true;
            }
            if(mnem.equals("rts")) {
               if(currentScope != null && program != null) {
                  dk.camelot64.kickc.model.symbols.Symbol sym = program.getScope().getLocalSymbol(currentScope);
                  if(sym instanceof dk.camelot64.kickc.model.symbols.Procedure) {
                     dk.camelot64.kickc.model.symbols.Procedure proc = (dk.camelot64.kickc.model.symbols.Procedure) sym;
                     if(dk.camelot64.kickc.model.types.SymbolType.VOID.equals(proc.getReturnType())) {
                        return true;
                     }
                  }
               }
               return false;
            }
            if(mnem.equals("jmp")) {
               String target = inst.getOperand1();
               if(target != null && visitedLabels.add(target.trim())) {
                  int targetIdx = findLabelIndex(allLines, target.trim(), m);
                  if(targetIdx != -1) {
                     return isADeadAlongPath(allLines, targetIdx + 1, visitedLabels, currentScope, program);
                  }
               }
               return false;
            }
            if(mnem.startsWith("b")) {
               return false;
            }
            continue;
         }
         break;
      }
      return false;
   }

   private static boolean isXDeadAlongPath(List<LineRef> allLines, int startIdx, Set<String> visitedLabels, String currentScope, dk.camelot64.kickc.model.Program program) {
      for(int m = startIdx; m < allLines.size(); m++) {
         LineRef cand = allLines.get(m);
         if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
         if(cand.line instanceof AsmLabel) continue;
         if(cand.line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) cand.line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) return false;
            String mnem = op.getMnemonic();
            if(mnem.equals("ldx") || mnem.equals("tax") || mnem.equals("tsx") || mnem.equals("lax")) {
               return true;
            }
            if(mnem.equals("stx") || mnem.equals("cpx") || mnem.equals("inx") || mnem.equals("dex") ||
               mnem.equals("txa") || mnem.equals("txs") || mnem.equals("axs") || mnem.equals("sax") ||
               mnem.equals("phx")) {
               return false;
            }
            if(mnem.equals("jsr")) {
               return true;
            }
            if(mnem.equals("rts")) {
               if(currentScope != null && program != null) {
                  dk.camelot64.kickc.model.symbols.Symbol sym = program.getScope().getLocalSymbol(currentScope);
                  if(sym instanceof dk.camelot64.kickc.model.symbols.Procedure) {
                     dk.camelot64.kickc.model.symbols.Procedure proc = (dk.camelot64.kickc.model.symbols.Procedure) sym;
                     if(dk.camelot64.kickc.model.types.SymbolType.VOID.equals(proc.getReturnType())) {
                        return true;
                     }
                  }
               }
               return false;
            }
            if(mnem.equals("jmp")) {
               String target = inst.getOperand1();
               if(target != null && visitedLabels.add(target.trim())) {
                  int targetIdx = findLabelIndex(allLines, target.trim(), m);
                  if(targetIdx != -1) {
                     return isXDeadAlongPath(allLines, targetIdx + 1, visitedLabels, currentScope, program);
                  }
               }
               return false;
            }
            if(mnem.startsWith("b")) {
               return false;
            }
            continue;
         }
         break;
      }
      return false;
   }
}
