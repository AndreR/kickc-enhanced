package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.asm.AsmFormat;
import dk.camelot64.kickc.asm.AsmInlineKickAsm;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.asm.AsmSegment;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.PhiTransitions;
import dk.camelot64.kickc.model.ProcedureCompilation;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementCall;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementJump;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.statements.StatementReturn;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.PointerDereferenceIndexed;
import dk.camelot64.kickc.model.values.PointerDereferenceSimple;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selective 6502 stack-based bytecode VM compiler and runtime emitter.
 * <p>
 * Procedures annotated with {@code __bytecode} / {@code #pragma bytecode} that satisfy
 * {@link #canCompileToBytecode(Program, Procedure)} are compiled to a 7-byte entry stub
 * ({@code lda #<stream; ldx #>stream; jmp __bc_vm_exec}) followed by a compact byte stream.
 * If at least one procedure is emitted as bytecode, {@link #emitVmInterpreter(AsmProgram)}
 * emits the shared 6502 VM runtime once per program; otherwise 0 bytes of runtime overhead are added.
 */
public final class Pass4BytecodeGenerator {

   public static final int BC_OP_RET         = 0x00;
   public static final int BC_OP_PUSH_IMM8   = 0x01;
   public static final int BC_OP_LOAD_ABS8   = 0x02;
   public static final int BC_OP_STORE_ABS8  = 0x03;
   public static final int BC_OP_LOAD_ZP8    = 0x04;
   public static final int BC_OP_STORE_ZP8   = 0x05;
   public static final int BC_OP_ADD8        = 0x06;
   public static final int BC_OP_SUB8        = 0x07;
   public static final int BC_OP_AND8        = 0x08;
   public static final int BC_OP_ORA8        = 0x09;
   public static final int BC_OP_EOR8        = 0x0A;
   public static final int BC_OP_JMP         = 0x0B;
   public static final int BC_OP_JZ          = 0x0C;
   public static final int BC_OP_JNZ         = 0x0D;
   public static final int BC_OP_CALL_NATIVE = 0x0E;

   public static final String VM_EXEC_LABEL = "__bc_vm_exec";

   private Pass4BytecodeGenerator() {
   }

   /**
    * Checks conservatively whether all statements and PHI transitions of {@code procedure}
    * can be lowered cleanly to the 8-bit stack VM bytecode ISA.
    */
   public static boolean canCompileToBytecode(Program program, Procedure procedure) {
      if(program == null || procedure == null || !procedure.isDeclaredBytecode()) {
         return false;
      }
      if(procedure.getInterruptType() != null || procedure.isDeclaredIntrinsic() || procedure.getFixedAddress() != null) {
         return false;
      }
      if(procedure.getReturnType() == null || !SymbolType.VOID.equals(procedure.getReturnType())) {
         return false;
      }
      if(!procedure.getBank().isCommon()) {
         return false;
      }
      for(Variable param : procedure.getParameters()) {
         Registers.Register alloc = param.getAllocation();
         if(!(alloc instanceof Registers.RegisterZpMem) && !(alloc instanceof Registers.RegisterMainMem)) {
            return false;
         }
         if(param.getType() == null || param.getType().getSizeBytes() != 1) {
            return false;
         }
      }

      ProcedureCompilation pc = program.getProcedureCompilation(procedure.getRef());
      if(pc == null || pc.getGraph() == null) {
         return false;
      }
      List<Graph.Block> blocks = pc.getGraph().getAllBlocks();
      if(blocks.isEmpty()) {
         return false;
      }

      for(Graph.Block block : blocks) {
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementPhiBlock) {
               continue;
            } else if(stmt instanceof StatementAssignment sa) {
               if(!isSupportedAssignment(program, sa)) {
                  return false;
               }
            } else if(stmt instanceof StatementConditionalJump cond) {
               if(!isSupportedCondJump(program, cond)) {
                  return false;
               }
            } else if(stmt instanceof StatementJump) {
               // Always supported
            } else if(stmt instanceof StatementCall call) {
               if(!isSupportedCall(program, call, block, pc.getGraph())) {
                  return false;
               }
            } else if(stmt instanceof StatementReturn ret) {
               if(ret.getValue() != null) {
                  return false;
               }
            } else {
               return false;
            }
         }

         Graph.Block defSucc = pc.getGraph().getDefaultSuccessor(block);
         if(!areEdgePhiAssignmentsSupported(program, block, defSucc)) {
            return false;
         }
         Graph.Block condSucc = pc.getGraph().getConditionalSuccessor(block);
         if(!areEdgePhiAssignmentsSupported(program, block, condSucc)) {
            return false;
         }
      }
      return true;
   }

   /**
    * Emits the 7-byte VM dispatch stub at the procedure's entry point and the compacted
    * {@code .byte} bytecode stream immediately following it inside the current procedure scope.
    */
   public static boolean emitBytecodeProcedure(Program program, Procedure procedure, AsmProgram asm) {
      if(!canCompileToBytecode(program, procedure)) {
         return false;
      }
      ProcedureCompilation pc = program.getProcedureCompilation(procedure.getRef());
      ControlFlowGraph cfg = pc.getGraph();
      List<Graph.Block> blocks = cfg.getAllBlocks();
      ScopeRef codeScope = procedure.getRef();

      String procFixName = AsmFormat.asmFix(procedure.getLocalName());
      String streamLabel = "__bc_stream_" + procFixName;

      // 7-byte native entry stub: loads stream address into A/X and jumps to shared VM
      asm.addInstruction("lda", CpuAddressingMode.IMM, "<" + streamLabel, false);
      asm.addInstruction("ldx", CpuAddressingMode.IMM, ">" + streamLabel, false);
      asm.addInstruction("jmp", CpuAddressingMode.ABS, VM_EXEC_LABEL, false);

      Map<Graph.Block, String> blockLabels = new IdentityHashMap<>();
      for(int i = 0; i < blocks.size(); i++) {
         blockLabels.put(blocks.get(i), "__bc_blk_" + procFixName + "_" + i);
      }

      StreamEmitter emitter = new StreamEmitter(program, codeScope);
      emitter.appendLabel(streamLabel);

      int phiBridgeCounter = 0;

      for(int i = 0; i < blocks.size(); i++) {
         Graph.Block block = blocks.get(i);
         emitter.appendLabel(blockLabels.get(block));
         List<PhiBridge> bridges = new ArrayList<>();

         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementPhiBlock) {
               continue;
            } else if(stmt instanceof StatementAssignment sa) {
               emitter.emitAssignment(sa);
            } else if(stmt instanceof StatementConditionalJump cond) {
               Graph.Block destBlock = cfg.getBlock(cond.getDestination());
               PhiTransitions.PhiTransition transition = getTransition(program, block, destBlock);
               String targetLabel;
               if(transition != null && !transition.getAssignments().isEmpty()) {
                  targetLabel = "__bc_phi_" + procFixName + "_" + (phiBridgeCounter++);
                  bridges.add(new PhiBridge(targetLabel, transition, blockLabels.get(destBlock)));
               } else {
                  targetLabel = blockLabels.get(destBlock);
               }
               emitter.emitCondJump(cond, targetLabel);
            } else if(stmt instanceof StatementJump jmp) {
               Graph.Block destBlock = cfg.getBlock(jmp.getDestination());
               PhiTransitions.PhiTransition transition = getTransition(program, block, destBlock);
               if(transition != null) {
                  emitter.emitPhiTransition(transition);
               }
               emitter.emitAddr(BC_OP_JMP, blockLabels.get(destBlock));
            } else if(stmt instanceof StatementCall call) {
               Graph.Block callSucc = cfg.getCallSuccessor(block);
               PhiTransitions.PhiTransition callTrans = getTransition(program, block, callSucc);
               if(callTrans != null) {
                  emitter.emitPhiTransition(callTrans);
               }
               Procedure callee = program.getScope().getProcedure(call.getProcedure());
               String targetAsm = AsmFormat.getAsmSymbolName(program, callee, codeScope);
               emitter.emitAddr(BC_OP_CALL_NATIVE, targetAsm);
            } else if(stmt instanceof StatementReturn) {
               emitter.emitOp(BC_OP_RET);
            }
         }

         Graph.Block defaultSucc = cfg.getDefaultSuccessor(block);
         if(defaultSucc != null) {
            PhiTransitions.PhiTransition defTrans = getTransition(program, block, defaultSucc);
            if(defTrans != null) {
               emitter.emitPhiTransition(defTrans);
            }
            Graph.Block nextBlock = (i + 1 < blocks.size()) ? blocks.get(i + 1) : null;
            if(!defaultSucc.equals(nextBlock) || !bridges.isEmpty()) {
               emitter.emitAddr(BC_OP_JMP, blockLabels.get(defaultSucc));
            }
         }

         for(PhiBridge bridge : bridges) {
            emitter.appendLabel(bridge.label);
            emitter.emitPhiTransition(bridge.transition);
            emitter.emitAddr(BC_OP_JMP, bridge.targetBlockLabel);
         }
      }

      asm.addLine(new AsmInlineKickAsm(
            emitter.build(),
            (long) emitter.byteCount,
            0L,
            CpuClobber.CLOBBER_ALL
      ));
      return true;
   }

   /**
    * Emits the shared 6502 bytecode virtual machine interpreter and its runtime state once per program.
    */
   public static void emitVmInterpreter(AsmProgram asm) {
      asm.startChunk(ScopeRef.ROOT, null, "Bytecode VM Runtime");
      asm.addLine(new AsmSegment(Scope.SEGMENT_CODE_DEFAULT));

      String vmCode =
            VM_EXEC_LABEL + ": {\n" +
            "  sta $fe\n" +
            "  stx $ff\n" +
            "__bc_loop:\n" +
            "  jsr __bc_fetch\n" +
            "  tax\n" +
            "  lda __bc_tbl_hi,x\n" +
            "  pha\n" +
            "  lda __bc_tbl_lo,x\n" +
            "  pha\n" +
            "  rts\n" +
            "__bc_fetch:\n" +
            "  ldy #0\n" +
            "  lda ($fe),y\n" +
            "  inc $fe\n" +
            "  bne !+\n" +
            "  inc $ff\n" +
            "!:\n" +
            "  rts\n" +
            "__bc_push:\n" +
            "  ldx __bc_sp\n" +
            "  sta __bc_stack,x\n" +
            "  inc __bc_sp\n" +
            "  rts\n" +
            "__bc_pop:\n" +
            "  dec __bc_sp\n" +
            "  ldx __bc_sp\n" +
            "  lda __bc_stack,x\n" +
            "  rts\n" +
            "__bc_op_ret:\n" +
            "  rts\n" +
            "__bc_op_push_imm8:\n" +
            "  jsr __bc_fetch\n" +
            "__bc_push_loop:\n" +
            "  jsr __bc_push\n" +
            "  jmp __bc_loop\n" +
            "__bc_op_load_abs8:\n" +
            "  jsr __bc_fetch\n" +
            "  sta __bc_load_abs+1\n" +
            "  jsr __bc_fetch\n" +
            "  sta __bc_load_abs+2\n" +
            "  jsr __bc_load_abs\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_store_abs8:\n" +
            "  jsr __bc_fetch\n" +
            "  sta __bc_store_abs+1\n" +
            "  jsr __bc_fetch\n" +
            "  sta __bc_store_abs+2\n" +
            "  jsr __bc_pop\n" +
            "  jsr __bc_store_abs\n" +
            "  jmp __bc_loop\n" +
            "__bc_op_load_zp8:\n" +
            "  jsr __bc_fetch\n" +
            "  tax\n" +
            "  lda $00,x\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_store_zp8:\n" +
            "  jsr __bc_fetch\n" +
            "  pha\n" +
            "  jsr __bc_pop\n" +
            "  tay\n" +
            "  pla\n" +
            "  tax\n" +
            "  tya\n" +
            "  sta $00,x\n" +
            "  jmp __bc_loop\n" +
            "__bc_op_add8:\n" +
            "  jsr __bc_pop\n" +
            "  sta __bc_tmp\n" +
            "  jsr __bc_pop\n" +
            "  clc\n" +
            "  adc __bc_tmp\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_sub8:\n" +
            "  jsr __bc_pop\n" +
            "  sta __bc_tmp\n" +
            "  jsr __bc_pop\n" +
            "  sec\n" +
            "  sbc __bc_tmp\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_and8:\n" +
            "  jsr __bc_pop\n" +
            "  sta __bc_tmp\n" +
            "  jsr __bc_pop\n" +
            "  and __bc_tmp\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_ora8:\n" +
            "  jsr __bc_pop\n" +
            "  sta __bc_tmp\n" +
            "  jsr __bc_pop\n" +
            "  ora __bc_tmp\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_eor8:\n" +
            "  jsr __bc_pop\n" +
            "  sta __bc_tmp\n" +
            "  jsr __bc_pop\n" +
            "  eor __bc_tmp\n" +
            "  jmp __bc_push_loop\n" +
            "__bc_op_jmp:\n" +
            "  jsr __bc_fetch\n" +
            "  pha\n" +
            "  jsr __bc_fetch\n" +
            "  sta $ff\n" +
            "  pla\n" +
            "  sta $fe\n" +
            "  jmp __bc_loop\n" +
            "__bc_op_jz:\n" +
            "  jsr __bc_pop\n" +
            "  beq __bc_op_jmp\n" +
            "__bc_skip2:\n" +
            "  jsr __bc_fetch\n" +
            "  jsr __bc_fetch\n" +
            "  jmp __bc_loop\n" +
            "__bc_op_jnz:\n" +
            "  jsr __bc_pop\n" +
            "  bne __bc_op_jmp\n" +
            "  jmp __bc_skip2\n" +
            "__bc_op_call_native:\n" +
            "  jsr __bc_fetch\n" +
            "  sta __bc_call_abs+1\n" +
            "  jsr __bc_fetch\n" +
            "  sta __bc_call_abs+2\n" +
            "  lda $fe\n" +
            "  pha\n" +
            "  lda $ff\n" +
            "  pha\n" +
            "  lda __bc_vreg\n" +
            "  ldx __bc_vreg+1\n" +
            "  ldy __bc_vreg+2\n" +
            "  jsr __bc_call_abs\n" +
            "  sta __bc_vreg\n" +
            "  stx __bc_vreg+1\n" +
            "  sty __bc_vreg+2\n" +
            "  pla\n" +
            "  sta $ff\n" +
            "  pla\n" +
            "  sta $fe\n" +
            "  jmp __bc_loop\n" +
            "__bc_tbl_lo:\n" +
            "  .byte <(__bc_op_ret-1), <(__bc_op_push_imm8-1), <(__bc_op_load_abs8-1), <(__bc_op_store_abs8-1)\n" +
            "  .byte <(__bc_op_load_zp8-1), <(__bc_op_store_zp8-1), <(__bc_op_add8-1), <(__bc_op_sub8-1)\n" +
            "  .byte <(__bc_op_and8-1), <(__bc_op_ora8-1), <(__bc_op_eor8-1), <(__bc_op_jmp-1)\n" +
            "  .byte <(__bc_op_jz-1), <(__bc_op_jnz-1), <(__bc_op_call_native-1)\n" +
            "__bc_tbl_hi:\n" +
            "  .byte >(__bc_op_ret-1), >(__bc_op_push_imm8-1), >(__bc_op_load_abs8-1), >(__bc_op_store_abs8-1)\n" +
            "  .byte >(__bc_op_load_zp8-1), >(__bc_op_store_zp8-1), >(__bc_op_add8-1), >(__bc_op_sub8-1)\n" +
            "  .byte >(__bc_op_and8-1), >(__bc_op_ora8-1), >(__bc_op_eor8-1), >(__bc_op_jmp-1)\n" +
            "  .byte >(__bc_op_jz-1), >(__bc_op_jnz-1), >(__bc_op_call_native-1)\n" +
            "}";
      asm.addLine(new AsmInlineKickAsm(vmCode, 275L, 0L, CpuClobber.CLOBBER_ALL));

      asm.addLine(new AsmSegment(Scope.SEGMENT_DATA_DEFAULT));
      String vmData =
            "__bc_sp: .byte 0\n" +
            "__bc_tmp: .byte 0\n" +
            "__bc_stack: .fill 32, 0\n" +
            "__bc_vreg: .fill 4, 0\n" +
            "__bc_load_abs: lda $0000\n" +
            "  rts\n" +
            "__bc_store_abs: sta $0000\n" +
            "  rts\n" +
            "__bc_call_abs: jmp $0000";
      asm.addLine(new AsmInlineKickAsm(vmData, 49L, 0L, CpuClobber.CLOBBER_NONE));
   }

   private static PhiTransitions.PhiTransition getTransition(Program program, Graph.Block fromBlock, Graph.Block toBlock) {
      if(toBlock == null || !toBlock.hasPhiBlock() || program.getPhiTransitions() == null) {
         return null;
      }
      PhiTransitions phiTransitions = program.getPhiTransitions().get(toBlock.getLabel());
      return phiTransitions != null ? phiTransitions.getTransition(fromBlock) : null;
   }

   private static boolean areEdgePhiAssignmentsSupported(Program program, Graph.Block fromBlock, Graph.Block toBlock) {
      PhiTransitions.PhiTransition transition = getTransition(program, fromBlock, toBlock);
      if(transition == null) {
         return true;
      }
      for(PhiTransitions.PhiTransition.PhiAssignment pa : transition.getAssignments()) {
         if(!isSupportedLValue(program, pa.getVariable()) || !isSupportedRValue(program, pa.getrValue())) {
            return false;
         }
      }
      return true;
   }

   private static boolean isSupportedAssignment(Program program, StatementAssignment sa) {
      if(!isSupportedLValue(program, sa.getlValue())) {
         return false;
      }
      Operator op = sa.getOperator();
      if(op == null) {
         return sa.getrValue1() == null && isSupportedRValue(program, sa.getrValue2());
      }
      if(sa.getrValue1() == null) {
         if(Operators.DEREF.equals(op)) {
            RValue ptr = unwrapByteCast(sa.getrValue2());
            return ptr instanceof ConstantValue;
         }
         if(Operators.BOOL_NOT.equals(op) || Operators.NEG.equals(op)
               || Operators.INCREMENT.equals(op) || Operators.DECREMENT.equals(op)
               || Operators.BYTE0.equals(op) || Operators.CAST_BYTE.equals(op) || Operators.CAST_SBYTE.equals(op)) {
            return isSupportedRValue(program, sa.getrValue2());
         }
         return false;
      }
      if(Operators.DEREF_IDX.equals(op)) {
         return unwrapByteCast(sa.getrValue1()) instanceof ConstantValue
               && unwrapByteCast(sa.getrValue2()) instanceof ConstantValue;
      }
      if(!isSupportedRValue(program, sa.getrValue1()) || !isSupportedRValue(program, sa.getrValue2())) {
         return false;
      }
      return Operators.PLUS.equals(op)
            || Operators.MINUS.equals(op)
            || Operators.BOOL_AND.equals(op)
            || Operators.BOOL_OR.equals(op)
            || Operators.BOOL_XOR.equals(op);
   }

   private static boolean isSupportedCondJump(Program program, StatementConditionalJump cond) {
      Operator op = cond.getOperator();
      if(op == null) {
         return cond.getrValue1() == null && isSupportedRValue(program, cond.getrValue2());
      }
      if(Operators.LOGIC_NOT.equals(op)) {
         return cond.getrValue1() == null && isSupportedRValue(program, cond.getrValue2());
      }
      if(Operators.EQ.equals(op) || Operators.NEQ.equals(op)) {
         return isSupportedRValue(program, cond.getrValue1()) && isSupportedRValue(program, cond.getrValue2());
      }
      return false;
   }

   private static boolean isSupportedCall(Program program, StatementCall call, Graph.Block block, ControlFlowGraph cfg) {
      if(call.getlValue() != null) {
         return false;
      }
      if(call.getParameters() != null && !call.getParameters().isEmpty()) {
         return false;
      }
      Procedure callee = program.getScope().getProcedure(call.getProcedure());
      if(callee == null || callee.isDeclaredIntrinsic() || callee.getFixedAddress() != null) {
         return false;
      }
      if(!callee.getBank().isCommon() || !SymbolType.VOID.equals(callee.getReturnType())) {
         return false;
      }
      Graph.Block callSucc = cfg.getCallSuccessor(block);
      return areEdgePhiAssignmentsSupported(program, block, callSucc);
   }

   private static boolean isSupportedLValue(Program program, LValue lValue) {
      if(lValue instanceof VariableRef vr) {
         Variable var = program.getScope().getVariable(vr);
         if(var == null || var.getType() == null || var.getType().getSizeBytes() != 1) {
            return false;
         }
         Registers.Register alloc = var.getAllocation();
         return alloc instanceof Registers.RegisterZpMem
               || alloc instanceof Registers.RegisterMainMem
               || (alloc != null && !alloc.isMem() && vregOffset(alloc.getType()) >= 0);
      }
      if(lValue instanceof PointerDereferenceSimple simple) {
         SymbolType elemType = SymbolTypeInference.inferType(program.getScope(), simple);
         return elemType != null && elemType.getSizeBytes() == 1 && unwrapByteCast(simple.getPointer()) instanceof ConstantValue;
      }
      if(lValue instanceof PointerDereferenceIndexed idx) {
         SymbolType elemType = SymbolTypeInference.inferType(program.getScope(), idx);
         return elemType != null && elemType.getSizeBytes() == 1
               && unwrapByteCast(idx.getPointer()) instanceof ConstantValue
               && unwrapByteCast(idx.getIndex()) instanceof ConstantValue;
      }
      return false;
   }

   private static boolean isSupportedRValue(Program program, RValue rValue) {
      RValue v = unwrapByteCast(rValue);
      if(v == null) {
         return false;
      }
      if(v instanceof ConstantValue) {
         SymbolType t = ((ConstantValue) v).getType(program.getScope());
         return t == null || SymbolType.NUMBER.equals(t) || t.getSizeBytes() <= 2;
      }
      if(v instanceof VariableRef vr) {
         Variable var = program.getScope().getVariable(vr);
         if(var == null || var.getType() == null || var.getType().getSizeBytes() != 1) {
            return false;
         }
         Registers.Register alloc = var.getAllocation();
         return alloc instanceof Registers.RegisterConstant
               || alloc instanceof Registers.RegisterZpMem
               || alloc instanceof Registers.RegisterMainMem
               || (alloc != null && !alloc.isMem() && vregOffset(alloc.getType()) >= 0);
      }
      if(v instanceof PointerDereferenceSimple simple) {
         SymbolType elemType = SymbolTypeInference.inferType(program.getScope(), simple);
         return elemType != null && elemType.getSizeBytes() == 1 && unwrapByteCast(simple.getPointer()) instanceof ConstantValue;
      }
      if(v instanceof PointerDereferenceIndexed idx) {
         SymbolType elemType = SymbolTypeInference.inferType(program.getScope(), idx);
         return elemType != null && elemType.getSizeBytes() == 1
               && unwrapByteCast(idx.getPointer()) instanceof ConstantValue
               && unwrapByteCast(idx.getIndex()) instanceof ConstantValue;
      }
      return false;
   }

   private static RValue unwrapByteCast(RValue rValue) {
      RValue cur = rValue;
      while(cur instanceof CastValue cv) {
         SymbolType toType = cv.getToType();
         if(toType == null || (toType.getSizeBytes() != 1 && !(toType instanceof dk.camelot64.kickc.model.types.SymbolTypePointer))) {
            break;
         }
         cur = cv.getValue();
      }
      return cur;
   }

   private static int vregOffset(Registers.RegisterType type) {
      return switch(type) {
         case REG_A -> 0;
         case REG_X -> 1;
         case REG_Y -> 2;
         case REG_Z -> 3;
         default -> -1;
      };
   }

   private static boolean isLiteralZero(Program program, RValue rValue) {
      RValue v = unwrapByteCast(rValue);
      if(!(v instanceof ConstantValue cv)) {
         return false;
      }
      try {
         ConstantLiteral<?> lit = cv.calculateLiteral(program.getScope());
         return lit instanceof ConstantInteger ci && ci.getInteger() == 0L;
      } catch(Exception e) {
         return false;
      }
   }

   private static final class PhiBridge {
      final String label;
      final PhiTransitions.PhiTransition transition;
      final String targetBlockLabel;

      PhiBridge(String label, PhiTransitions.PhiTransition transition, String targetBlockLabel) {
         this.label = label;
         this.transition = transition;
         this.targetBlockLabel = targetBlockLabel;
      }
   }

   private static final class StreamEmitter {
      private final Program program;
      private final ScopeRef codeScope;
      private final StringBuilder sb = new StringBuilder();
      int byteCount = 0;

      StreamEmitter(Program program, ScopeRef codeScope) {
         this.program = program;
         this.codeScope = codeScope;
      }

      void appendLabel(String label) {
         sb.append(label).append(":\n");
      }

      void emitOp(int op) {
         sb.append(String.format("  .byte $%02x\n", op));
         byteCount += 1;
      }

      void emitByteArg(int op, String byteExpr) {
         sb.append(String.format("  .byte $%02x, %s\n", op, byteExpr));
         byteCount += 2;
      }

      void emitImm(String immExpr) {
         sb.append(String.format("  .byte $%02x, <(%s)\n", BC_OP_PUSH_IMM8, immExpr));
         byteCount += 2;
      }

      void emitAddr(int op, String addrExpr) {
         sb.append(String.format("  .byte $%02x, <(%s), >(%s)\n", op, addrExpr, addrExpr));
         byteCount += 3;
      }

      String build() {
         return sb.toString();
      }

      void emitPhiTransition(PhiTransitions.PhiTransition transition) {
         for(PhiTransitions.PhiTransition.PhiAssignment pa : transition.getAssignments()) {
            emitPushRValue(pa.getrValue());
            emitStoreLValue(pa.getVariable());
         }
      }

      void emitAssignment(StatementAssignment sa) {
         Operator op = sa.getOperator();
         if(op == null) {
            emitPushRValue(sa.getrValue2());
         } else if(sa.getrValue1() == null) {
            if(Operators.DEREF.equals(op)) {
               ConstantValue ptr = (ConstantValue) unwrapByteCast(sa.getrValue2());
               emitAddr(BC_OP_LOAD_ABS8, AsmFormat.getAsmConstant(program, ptr, 99, codeScope));
            } else if(Operators.BOOL_NOT.equals(op)) {
               emitPushRValue(sa.getrValue2());
               emitImm("$ff");
               emitOp(BC_OP_EOR8);
            } else if(Operators.NEG.equals(op)) {
               emitImm("0");
               emitPushRValue(sa.getrValue2());
               emitOp(BC_OP_SUB8);
            } else if(Operators.INCREMENT.equals(op)) {
               emitPushRValue(sa.getrValue2());
               emitImm("1");
               emitOp(BC_OP_ADD8);
            } else if(Operators.DECREMENT.equals(op)) {
               emitPushRValue(sa.getrValue2());
               emitImm("1");
               emitOp(BC_OP_SUB8);
            } else {
               emitPushRValue(sa.getrValue2());
            }
         } else if(Operators.DEREF_IDX.equals(op)) {
            ConstantValue ptr = (ConstantValue) unwrapByteCast(sa.getrValue1());
            ConstantValue idx = (ConstantValue) unwrapByteCast(sa.getrValue2());
            String expr = "(" + AsmFormat.getAsmConstant(program, ptr, 99, codeScope) + ")+("
                  + AsmFormat.getAsmConstant(program, idx, 99, codeScope) + ")";
            emitAddr(BC_OP_LOAD_ABS8, expr);
         } else {
            emitPushRValue(sa.getrValue1());
            emitPushRValue(sa.getrValue2());
            if(Operators.PLUS.equals(op)) {
               emitOp(BC_OP_ADD8);
            } else if(Operators.MINUS.equals(op)) {
               emitOp(BC_OP_SUB8);
            } else if(Operators.BOOL_AND.equals(op)) {
               emitOp(BC_OP_AND8);
            } else if(Operators.BOOL_OR.equals(op)) {
               emitOp(BC_OP_ORA8);
            } else if(Operators.BOOL_XOR.equals(op)) {
               emitOp(BC_OP_EOR8);
            }
         }
         emitStoreLValue(sa.getlValue());
      }

      void emitCondJump(StatementConditionalJump cond, String targetLabel) {
         Operator op = cond.getOperator();
         if(op == null) {
            emitPushRValue(cond.getrValue2());
            emitAddr(BC_OP_JNZ, targetLabel);
         } else if(Operators.LOGIC_NOT.equals(op)) {
            emitPushRValue(cond.getrValue2());
            emitAddr(BC_OP_JZ, targetLabel);
         } else if(Operators.EQ.equals(op)) {
            emitPushRValue(cond.getrValue1());
            if(!isLiteralZero(program, cond.getrValue2())) {
               emitPushRValue(cond.getrValue2());
               emitOp(BC_OP_SUB8);
            }
            emitAddr(BC_OP_JZ, targetLabel);
         } else if(Operators.NEQ.equals(op)) {
            emitPushRValue(cond.getrValue1());
            if(!isLiteralZero(program, cond.getrValue2())) {
               emitPushRValue(cond.getrValue2());
               emitOp(BC_OP_SUB8);
            }
            emitAddr(BC_OP_JNZ, targetLabel);
         }
      }

      void emitPushRValue(RValue rValue) {
         RValue v = unwrapByteCast(rValue);
         if(v instanceof ConstantValue cv) {
            emitImm(AsmFormat.getAsmConstant(program, cv, 99, codeScope));
            return;
         }
         if(v instanceof VariableRef vr) {
            Variable var = program.getScope().getVariable(vr);
            Registers.Register alloc = var.getAllocation();
            if(alloc instanceof Registers.RegisterConstant rc) {
               emitImm(AsmFormat.getAsmConstant(program, rc.getConstantValue(), 99, codeScope));
            } else if(alloc instanceof Registers.RegisterZpMem) {
               emitByteArg(BC_OP_LOAD_ZP8, AsmFormat.getAsmSymbolName(program, var, codeScope));
            } else if(alloc instanceof Registers.RegisterMainMem memReg) {
               String addr = (var.getAsmName() != null || memReg.getAddress() == null)
                     ? AsmFormat.getAsmSymbolName(program, var, codeScope)
                     : AsmFormat.getAsmNumber(memReg.getAddress());
               emitAddr(BC_OP_LOAD_ABS8, addr);
            } else if(alloc != null && !alloc.isMem()) {
               int off = vregOffset(alloc.getType());
               emitAddr(BC_OP_LOAD_ABS8, off == 0 ? "__bc_vreg" : ("__bc_vreg+" + off));
            }
            return;
         }
         if(v instanceof PointerDereferenceSimple simple) {
            ConstantValue ptr = (ConstantValue) unwrapByteCast(simple.getPointer());
            emitAddr(BC_OP_LOAD_ABS8, AsmFormat.getAsmConstant(program, ptr, 99, codeScope));
            return;
         }
         if(v instanceof PointerDereferenceIndexed idx) {
            ConstantValue ptr = (ConstantValue) unwrapByteCast(idx.getPointer());
            ConstantValue idxConst = (ConstantValue) unwrapByteCast(idx.getIndex());
            String expr = "(" + AsmFormat.getAsmConstant(program, ptr, 99, codeScope) + ")+("
                  + AsmFormat.getAsmConstant(program, idxConst, 99, codeScope) + ")";
            emitAddr(BC_OP_LOAD_ABS8, expr);
         }
      }

      void emitStoreLValue(LValue lValue) {
         if(lValue instanceof VariableRef vr) {
            Variable var = program.getScope().getVariable(vr);
            Registers.Register alloc = var.getAllocation();
            if(alloc instanceof Registers.RegisterZpMem) {
               emitByteArg(BC_OP_STORE_ZP8, AsmFormat.getAsmSymbolName(program, var, codeScope));
            } else if(alloc instanceof Registers.RegisterMainMem memReg) {
               String addr = (var.getAsmName() != null || memReg.getAddress() == null)
                     ? AsmFormat.getAsmSymbolName(program, var, codeScope)
                     : AsmFormat.getAsmNumber(memReg.getAddress());
               emitAddr(BC_OP_STORE_ABS8, addr);
            } else if(alloc != null && !alloc.isMem()) {
               int off = vregOffset(alloc.getType());
               emitAddr(BC_OP_STORE_ABS8, off == 0 ? "__bc_vreg" : ("__bc_vreg+" + off));
            }
            return;
         }
         if(lValue instanceof PointerDereferenceSimple simple) {
            ConstantValue ptr = (ConstantValue) unwrapByteCast(simple.getPointer());
            emitAddr(BC_OP_STORE_ABS8, AsmFormat.getAsmConstant(program, ptr, 99, codeScope));
            return;
         }
         if(lValue instanceof PointerDereferenceIndexed idx) {
            ConstantValue ptr = (ConstantValue) unwrapByteCast(idx.getPointer());
            ConstantValue idxConst = (ConstantValue) unwrapByteCast(idx.getIndex());
            String expr = "(" + AsmFormat.getAsmConstant(program, ptr, 99, codeScope) + ")+("
                  + AsmFormat.getAsmConstant(program, idxConst, 99, codeScope) + ")";
            emitAddr(BC_OP_STORE_ABS8, expr);
         }
      }
   }
}
