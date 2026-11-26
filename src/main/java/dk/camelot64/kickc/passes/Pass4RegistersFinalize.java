package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAsm;
import dk.camelot64.kickc.model.symbols.*;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.types.SymbolTypeStruct;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.PointerDereference;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.values.Value;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.parser.KickCParser;
import dk.camelot64.kickc.parser.KickCParserBaseVisitor;

import java.util.*;

/**
 * Move register allocation from equivalence classes to RegisterAllocation.
 * Also reallocate and rename zero page registers in the equivalence classes.
 */
public class Pass4RegistersFinalize extends Pass2Base {

   /**
    * The current zero page used to create new registers when needed.
    */
   private int currentZp = 2;

   /** All reserved zeropage addresses not available for the compiler. */
   private List<Integer> reservedZp;

   /** True if zero-page overflow spilled at least one equivalence class to main memory. */
   private boolean spilledToMainMem = false;

   public boolean hasSpilledToMainMem() {
      return spilledToMainMem;
   }

   public Pass4RegistersFinalize(Program program) {
      super(program);
      this.reservedZp = new ArrayList<>();
      // Add all ZP's declared as reserved
      this.reservedZp.addAll(program.getReservedZps());
      // Add all ZP's declared as reserved in a procedure
      for(Procedure procedure : getSymbols().getAllProcedures(true)) {
         List<Integer> procedureReservedZps = procedure.getReservedZps();
         if(procedureReservedZps!=null) {
            this.reservedZp.addAll(procedureReservedZps);
         }
      }
      // Add all ZP's declared hardcoded register for a live variable
      for(Variable variable : getSymbols().getAllVariables(true)) {
         if(variable.getRegister() instanceof Registers.RegisterZpMem) {
            int zp = ((Registers.RegisterZpMem) variable.getRegister()).getZp();
            int sizeBytes = variable.getType().getSizeBytes();
            for(int i=0;i<sizeBytes; i++) {
               if(!reservedZp.contains(zp+i))
                  this.reservedZp.add(zp+i);
               getProgram().addReservedZp(zp+i);
            }
         }
      }
   }

   public void allocate(boolean reallocateZp, boolean overflowToMainMem) {
      LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet = getProgram().getLiveRangeEquivalenceClassSet();
      for(LiveRangeEquivalenceClass equivalenceClass : liveRangeEquivalenceClassSet.getEquivalenceClasses()) {
         for(VariableRef variableRef : equivalenceClass.getVariables()) {
            Variable variable = getProgram().getScope().getVariable(variableRef);
            Registers.Register declaredRegister = variable.getRegister(); //TODO: Handle register/memory/storage strategy differently!
            Registers.Register register = declaredRegister;
            if(declaredRegister !=null) {
               if(declaredRegister instanceof Registers.RegisterZpMem) {
                  int zp = ((Registers.RegisterZpMem) declaredRegister).getZp();
                  int bytes = variable.getType().getSizeBytes();
                  register = new Registers.RegisterZpMem(zp, bytes, true);
               } else if(declaredRegister instanceof Registers.RegisterMainMem) {
                  Long address = ((Registers.RegisterMainMem) declaredRegister).getAddress();
                  VariableRef varRef = ((Registers.RegisterMainMem) declaredRegister).getVariableRef();
                  int bytes = variable.getType().getSizeBytes();
                  register = new Registers.RegisterMainMem(varRef, bytes, address, true);
               } else if(equivalenceClass.getRegister()!=null && !declaredRegister.equals(equivalenceClass.getRegister())) {
                  throw new CompileError("Equivalence class has variables with different declared registers \n" +
                        " - equivalence class: " + equivalenceClass.toString(true) + "\n" +
                        " - one register: " + equivalenceClass.getRegister().toString() + "\n" +
                        " - other register: " + declaredRegister.toString()
                  );
               }
               equivalenceClass.setRegister(register);
            }
         }
      }

      
      if(reallocateZp) {
         // SSA-10: Conditionally reserve $FE/$FF scratch ZP when RegisterMainMem pointers exist
         if(hasMainMemPointer(liveRangeEquivalenceClassSet)) {
            reserveScratchZpFeFf();
         }
         reallocateMemRegisters(liveRangeEquivalenceClassSet, overflowToMainMem);
         // If ZP overflow moved a pointer to RegisterMainMem and $FE/$FF were not yet reserved, reserve and reallocate
         if(overflowToMainMem && (!reservedZp.contains(0xFE) || !reservedZp.contains(0xFF)) && hasMainMemPointer(liveRangeEquivalenceClassSet)) {
            reserveScratchZpFeFf();
            currentZp = 2;
            reallocateMemRegisters(liveRangeEquivalenceClassSet, overflowToMainMem);
         }
      }
      liveRangeEquivalenceClassSet.storeRegisterAllocation();
      if(reallocateZp) {
         shortenAsmNames();
      }
   }

   private boolean hasMainMemPointer(LiveRangeEquivalenceClassSet equivalenceClassSet) {
      for(LiveRangeEquivalenceClass ec : equivalenceClassSet.getEquivalenceClasses()) {
         boolean isMainMemReg = ec.getRegister() instanceof Registers.RegisterMainMem;
         for(VariableRef varRef : ec.getVariables()) {
            Variable var = getProgram().getSymbolInfos().getVariable(varRef);
            if(var != null && var.getType() instanceof SymbolTypePointer) {
               if(isMainMemReg || var.isMemoryAreaMain() || var.getRegister() instanceof Registers.RegisterMainMem) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private void reserveScratchZpFeFf() {
      if(!reservedZp.contains(0xFE)) {
         reservedZp.add(0xFE);
         getProgram().addReservedZp(0xFE);
      }
      if(!reservedZp.contains(0xFF)) {
         reservedZp.add(0xFF);
         getProgram().addReservedZp(0xFF);
      }
   }

   /**
    * Shorten ASM names for variables and constants
    */
   private void shortenAsmNames() {
      Collection<Scope> allScopes = getProgram().getScope().getAllScopes(true);
      allScopes.add(getProgram().getScope());
      for(Scope scope : allScopes) {
         // Create initial short names
         for(Variable variable : scope.getAllVariables(false)) {
            if(variable.getAllocation() != null && variable.getAllocation().isMem()) {
               variable.setAsmName(variable.getLocalName());
            } else {
               variable.setAsmName(null);
            }
         }
         for(Variable constantVar : scope.getAllConstants(false)) {
            constantVar.setAsmName(constantVar.getLocalName());
         }

         // Maps short name to the allocated register.
         Map<String, Registers.Register> shortNames = new LinkedHashMap<>();
         // Shorten variable and constant names
         for(Variable variable : scope.getAllVariables(false)) {
            Registers.Register allocation = variable.getAllocation();
            if(allocation != null && allocation.isMem()) {
               shortenAsmName(shortNames, variable, allocation);
            }
         }
         for(Variable constantVar : scope.getAllConstants(false)) {
            Registers.Register allocation = new Registers.RegisterConstant(constantVar.getInitValue());
            shortenAsmName(shortNames, constantVar, allocation);
         }
      }
   }

   /**
    * Shorten the ASM name for a single variable.
    *
    * @param shortNames Map of allocated short names. Maps short name to the allocated register.
    * @param variable The variable to shorten the name for
    * @param allocation The register allocation for the variable
    */
   private void shortenAsmName(Map<String, Registers.Register> shortNames, Variable variable, Registers.Register allocation) {
      String asmName = variable.getAsmName();
      String prefix = asmName;
      if(asmName.contains("#")) {
         prefix = asmName.substring(0, asmName.indexOf("#"));
      }
      int suffix = 0;
      boolean found = false;
      while(!found) {
         String shortName = prefix + ((suffix==0)?"":("_"+suffix));
         if(shortNames.get(shortName) == null || shortNames.get(shortName).equals(allocation)) {
            // Short name is usable!
            variable.setAsmName(shortName);
            shortNames.put(shortName, allocation);
            found=true;
         }
         suffix++;
      }
   }

   /**
    * Reallocate all memory registers. Minimizes zeropage usage.
    *
    * @param equivalenceClassSet The live range equivalence classes
    * @param overflowToMainMem If true zeropage variables overflow into main memory if they do not fit on zeropage.
    */
   private void reallocateMemRegisters(LiveRangeEquivalenceClassSet equivalenceClassSet, boolean overflowToMainMem) {
      spilledToMainMem = false;
      List<LiveRangeEquivalenceClass> equivalenceClasses = new ArrayList<>(equivalenceClassSet.getEquivalenceClasses());
      final VariableRegisterWeights registerWeights = getProgram().getVariableRegisterWeights();
      final Set<VariableRef> dereferencedPointers = findDereferencedPointers();
      final Set<VariableRef> asmPriorityVars = new LinkedHashSet<>();
      final Set<VariableRef> asmIndirectVars = new LinkedHashSet<>();
      findAsmReferencedVariables(asmPriorityVars, asmIndirectVars);
      final Map<LiveRangeEquivalenceClass, Integer> zpPriorities = new HashMap<>(equivalenceClasses.size() * 2);
      for(LiveRangeEquivalenceClass ec : equivalenceClasses) {
         zpPriorities.put(ec, getZeroPageRetentionPriority(ec, asmPriorityVars, dereferencedPointers));
      }
      Collections.sort(equivalenceClasses, (o1, o2) -> {
         int p1 = zpPriorities.getOrDefault(o1, 0);
         int p2 = zpPriorities.getOrDefault(o2, 0);
         if(p1 != p2) {
            return Integer.compare(p2, p1);
         }
         return Double.compare(registerWeights.getTotalWeight(o2), registerWeights.getTotalWeight(o1));
      });

      for(LiveRangeEquivalenceClass equivalenceClass : equivalenceClasses) {
         Registers.Register register = equivalenceClass.getRegister();
         boolean reallocate = true;
         if(register!=null) {
            if(register.isHardware()) {
               // Do not allocate hardware registers
               reallocate = false;
            } else if(register.isAddressHardcoded()) {
               // Do not allocate registers with hardcoded address
               reallocate = false;
            }
         }
         if(reallocate) {
            String before = register == null ? null : register.toString();
            VariableRef variableRef = equivalenceClass.getVariables().get(0);
            Variable variable = getProgram().getSymbolInfos().getVariable(variableRef);
            if(variable.isMemoryAreaMain()) {
               register = new Registers.RegisterMainMem(variableRef, variable.getType().getSizeBytes(), null, false);
            }  else {
               register = allocateNewRegisterZp(variable);
               int zp = ((Registers.RegisterZpMem) register).getZp();
               int sizeBytes = variable.getType().getSizeBytes();
               if(overflowToMainMem) {
                  if (zp + sizeBytes > 0x100) {
                     VariableRef requiredZpVar = findRequiredZpVariable(equivalenceClass, asmIndirectVars);
                     if(requiredZpVar != null) {
                        throw new CompileError("Error! Zero-page exhausted: cannot allocate explicit __zp or indirect-ASM variable '" + requiredZpVar.getFullName() + "' to zero page.");
                     }
                     VariableRef targetRef = selectMainMemTarget(equivalenceClass);
                     // Zero-page exhausted - move to main memory instead
                     register = new Registers.RegisterMainMem(targetRef, variable.getType().getSizeBytes(), null, false);
                     spilledToMainMem = true;
                     getLog().append("Zero-page exhausted. Moving allocation to main memory " + targetRef.toString());
                  }
               }
            }
            equivalenceClass.setRegister(register);
            if(before == null || !before.equals(register.toString())) {
               getLog().append("Allocated " + (before == null ? "" : ("(was " + before + ") ")) + equivalenceClass.toString());
            }
         }
      }
   }

   /**
    * Demote CPU-register-allocated equivalence classes that suffer register clobbers after a
    * zero-page-to-main-memory spill (e.g. a spilled pointer whose main-memory dereference fragment
    * clobbers an extra CPU register).
    *
    * @param clobberedVars Alive variables whose CPU register allocation was clobbered
    * @return true if at least one equivalence class was demoted to memory
    */
   public boolean demoteClobberedRegistersToMemory(Collection<VariableRef> clobberedVars) {
      LiveRangeEquivalenceClassSet equivalenceClassSet = getProgram().getLiveRangeEquivalenceClassSet();
      Set<LiveRangeEquivalenceClass> toDemote = new LinkedHashSet<>();
      for(VariableRef varRef : clobberedVars) {
         LiveRangeEquivalenceClass ec = equivalenceClassSet.getEquivalenceClass(varRef);
         if(ec != null && ec.getRegister() != null && ec.getRegister().isHardware()) {
            toDemote.add(ec);
         }
      }
      boolean demoted = false;
      for(LiveRangeEquivalenceClass ec : toDemote) {
         boolean hasDeclaredHardwareReg = false;
         for(VariableRef vr : ec.getVariables()) {
            Variable v = getProgram().getSymbolInfos().getVariable(vr);
            if(v != null && v.getRegister() != null && v.getRegister().isHardware()) {
               hasDeclaredHardwareReg = true;
               break;
            }
         }
         if(hasDeclaredHardwareReg) {
            continue;
         }
         VariableRef targetRef = selectMainMemTarget(ec);
         Variable primary = getProgram().getSymbolInfos().getVariable(targetRef);
         int sizeBytes = primary.getType().getSizeBytes();
         Registers.Register oldReg = ec.getRegister();
         Registers.Register mainMemReg = new Registers.RegisterMainMem(targetRef, sizeBytes, null, false);
         ec.setRegister(mainMemReg);
         getLog().append("Post-spill clobber guard: demoted " + ec.toString() + " (was " + oldReg + ") to main memory " + targetRef);
         demoted = true;
      }
      if(demoted) {
         equivalenceClassSet.storeRegisterAllocation();
         shortenAsmNames();
      }
      return demoted;
   }

   /**
    * Select the primary variable to hold main-memory storage for a spilled or demoted equivalence class,
    * ensuring it is placed in a writable RAM data segment.
    */
   private VariableRef selectMainMemTarget(LiveRangeEquivalenceClass equivalenceClass) {
      VariableRef targetRef = equivalenceClass.getVariables().get(0);
      if(hasMultipleDataSegments(equivalenceClass)) {
         // Find a variable in the equivalence class that belongs to resident Data segment
         for(VariableRef vr : equivalenceClass.getVariables()) {
            Variable v = getProgram().getSymbolInfos().getVariable(vr);
            if(v != null && (Scope.SEGMENT_DATA_DEFAULT.equals(v.getDataSegment()) || v.getDataSegment() == null)) {
               targetRef = vr;
               break;
            }
         }
         Variable primary = getProgram().getSymbolInfos().getVariable(targetRef);
         if(primary != null && !Scope.SEGMENT_DATA_DEFAULT.equals(primary.getDataSegment())) {
            primary.setDataSegment(Scope.SEGMENT_DATA_DEFAULT);
         }
      } else {
         Variable primary = getProgram().getSymbolInfos().getVariable(targetRef);
         if(primary != null && getProgram().isSegmentRom(primary.getDataSegment())) {
            primary.setDataSegment(Scope.SEGMENT_DATA_DEFAULT);
         }
      }
      return targetRef;
   }

   /**
    * Collect all variables used as the pointer operand of an indirect pointer dereference.
    */
   private Set<VariableRef> findDereferencedPointers() {
      Set<VariableRef> derefPointers = new LinkedHashSet<>();
      ProgramValueIterator.execute(getGraph(), (programValue, currentStmt, stmtIt, currentBlock) -> {
         Value value = programValue.get();
         if(value instanceof PointerDereference) {
            RValue ptr = ((PointerDereference) value).getPointer();
            while(ptr instanceof CastValue) {
               ptr = ((CastValue) ptr).getValue();
            }
            if(ptr instanceof VariableRef) {
               derefPointers.add((VariableRef) ptr);
            }
         }
      });
      return derefPointers;
   }

   /**
    * Collect all variables referenced in inline ASM statements (StatementAsm) that require
    * top zero-page retention priority:
    * - Any pointer variable or explicit __zp variable referenced in StatementAsm
    * - Any variable referenced inside a zero-page indirect addressing mode operand (e.g. lda (ptr),y)
    */
   private void findAsmReferencedVariables(Set<VariableRef> asmPriorityVars, Set<VariableRef> asmIndirectVars) {
      for(Graph.Block block : getGraph().getAllBlocks()) {
         for(Statement stmt : block.getStatements()) {
            if(stmt instanceof StatementAsm stmtAsm) {
               Map<String, SymbolRef> referenced = stmtAsm.getReferenced();
               if(referenced == null || referenced.isEmpty()) {
                  continue;
               }
               for(SymbolRef ref : referenced.values()) {
                  if(ref instanceof VariableRef varRef) {
                     Variable var = getProgram().getSymbolInfos().getVariable(varRef);
                     if(var != null && (var.getType() instanceof SymbolTypePointer || var.isExplicitZp())) {
                        asmPriorityVars.add(varRef);
                     }
                  }
               }
               if(stmtAsm.getAsmLines() != null) {
                  collectAsmIndirectVariables(stmtAsm.getAsmLines(), referenced, asmPriorityVars, asmIndirectVars);
               }
            }
         }
      }
   }

   /**
    * Walk inline ASM parse tree to identify variables used in zero-page indirect addressing modes
    * (e.g., (ptr),y, (ptr,x), ((ptr)),z, ((ptr)), or non-JMP (ptr)).
    */
   private void collectAsmIndirectVariables(
         KickCParser.AsmLinesContext asmLines,
         Map<String, SymbolRef> referenced,
         Set<VariableRef> asmPriorityVars,
         Set<VariableRef> asmIndirectVars) {
      KickCParserBaseVisitor<Void> visitor = new KickCParserBaseVisitor<>() {
         private boolean inIndirectZpMode = false;

         private void visitIndirectExpr(KickCParser.AsmExprContext exprCtx) {
            if(exprCtx == null) return;
            boolean prev = inIndirectZpMode;
            inIndirectZpMode = true;
            try {
               visit(exprCtx);
            } finally {
               inIndirectZpMode = prev;
            }
         }

         @Override
         public Void visitAsmModeIndIdxXY(KickCParser.AsmModeIndIdxXYContext ctx) {
            visitIndirectExpr(ctx.asmExpr());
            return null;
         }

         @Override
         public Void visitAsmModeIndLongIdxXY(KickCParser.AsmModeIndLongIdxXYContext ctx) {
            visitIndirectExpr(ctx.asmExpr());
            return null;
         }

         @Override
         public Void visitAsmModeIdxIndXY(KickCParser.AsmModeIdxIndXYContext ctx) {
            visitIndirectExpr(ctx.asmExpr());
            return null;
         }

         @Override
         public Void visitAsmModeIndLong(KickCParser.AsmModeIndLongContext ctx) {
            visitIndirectExpr(ctx.asmExpr());
            return null;
         }

         @Override
         public Void visitAsmModeInd(KickCParser.AsmModeIndContext ctx) {
            boolean isJmp = false;
            if(ctx.getParent() instanceof KickCParser.AsmInstructionContext instrCtx) {
               String mnemonic = instrCtx.ASM_MNEMONIC().getText();
               isJmp = "jmp".equalsIgnoreCase(mnemonic);
            }
            if(!isJmp) {
               visitIndirectExpr(ctx.asmExpr());
            }
            return null;
         }

         @Override
         public Void visitAsmExprBinary(KickCParser.AsmExprBinaryContext ctx) {
            if(".".equals(ctx.getChild(1).getText())) {
               return null;
            }
            return super.visitAsmExprBinary(ctx);
         }

         @Override
         public Void visitAsmExprLabel(KickCParser.AsmExprLabelContext ctx) {
            if(inIndirectZpMode) {
               String label = ctx.ASM_NAME().getText();
               SymbolRef ref = referenced.get(label);
               if(ref instanceof VariableRef varRef) {
                  asmIndirectVars.add(varRef);
                  asmPriorityVars.add(varRef);
               }
            }
            return super.visitAsmExprLabel(ctx);
         }
      };
      visitor.visit(asmLines);
   }

   /**
    * Find a variable in the equivalence class that strictly requires zero-page allocation
    * (explicitly declared __zp or used in inline ASM zero-page indirect addressing).
    */
   private VariableRef findRequiredZpVariable(LiveRangeEquivalenceClass equivalenceClass, Set<VariableRef> asmIndirectVars) {
      for(VariableRef varRef : equivalenceClass.getVariables()) {
         Variable var = getProgram().getSymbolInfos().getVariable(varRef);
         if((var != null && var.isExplicitZp()) || asmIndirectVars.contains(varRef)) {
            return varRef;
         }
      }
      return null;
   }

   /**
    * Compute zero-page retention priority for an equivalence class.
    * Higher values are allocated to zero page first and spilled to main memory last:
    * 4: Explicit __zp variables or pointers/variables referenced in inline ASM (StatementAsm)
    * 3: Indirectly dereferenced pointers (spilling forces scratch ZP copying and may clobber CPU registers)
    * 2: Multi-segment equivalence classes
    * 1: Other pointer equivalence classes
    * 0: Non-pointer scalars
    */
   private int getZeroPageRetentionPriority(
         LiveRangeEquivalenceClass equivalenceClass,
         Set<VariableRef> asmPriorityVars,
         Set<VariableRef> dereferencedPointers) {
      boolean isDereferenced = false;
      boolean isPointer = false;
      for(VariableRef varRef : equivalenceClass.getVariables()) {
         Variable var = getProgram().getSymbolInfos().getVariable(varRef);
         if(asmPriorityVars.contains(varRef) || (var != null && var.isExplicitZp())) {
            return 4;
         }
         if(dereferencedPointers.contains(varRef)) {
            isDereferenced = true;
         }
         if(var != null && var.getType() instanceof SymbolTypePointer) {
            isPointer = true;
         }
      }
      if(isDereferenced) {
         return 3;
      }
      if(hasMultipleDataSegments(equivalenceClass)) {
         return 2;
      }
      return isPointer ? 1 : 0;
   }

   /**
    * Checks if an equivalence class contains variables originating from different data segments.
    */
   private boolean hasMultipleDataSegments(LiveRangeEquivalenceClass equivalenceClass) {
      String segment = null;
      for(VariableRef varRef : equivalenceClass.getVariables()) {
         Variable var = getProgram().getSymbolInfos().getVariable(varRef);
         if(var != null && var.getDataSegment() != null) {
            if(segment == null) {
               segment = var.getDataSegment();
            } else if(!segment.equals(var.getDataSegment())) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Allocate bytes on zeropage.
    * Avoids reserved zero page addresses.
    * @param size The number of bytes to allocate
    * @return The address of the first byte.
    */
   private int allocateZp(int size) {
      // Find a ZP sequence of size without any reserved ZP
      boolean reserved;
      do {
         reserved = false;
         int candidateZp = currentZp;
         for(int i=0;i<size;i++) {
            if(reservedZp.contains(Integer.valueOf(candidateZp+i))) {
               reserved = true;
               currentZp++;
               break;
            }
         }
      } while(reserved);
      // No reserved ZP
      int allocated = currentZp;
      currentZp += size;
      return allocated;
   }

   /**
    * Create a new register for a specific variable type.
    *
    * @param variable The variable to create a register for.
    * The register type created uses one or more zero page locations based on the variable type
    * @return The new zeropage register
    */
   private Registers.Register allocateNewRegisterZp(Variable variable) {
      SymbolType varType = variable.getType();
      if(SymbolType.BYTE.equals(varType) || SymbolType.NIBBLE.equals(varType)) {
         return new Registers.RegisterZpMem(allocateZp(1), 1);
      } else if(SymbolType.SBYTE.equals(varType)) {
         return new Registers.RegisterZpMem(allocateZp(1),1);
      } else if(SymbolType.WORD.equals(varType)) {
         Registers.RegisterZpMem registerZpWord =
               new Registers.RegisterZpMem(allocateZp(2), 2);
         return registerZpWord;
      } else if(SymbolType.SWORD.equals(varType)) {
         Registers.RegisterZpMem registerZpWord =
               new Registers.RegisterZpMem(allocateZp(2), 2);
         return registerZpWord;
      } else if(SymbolType.TRIBYTE.equals(varType) || SymbolType.STRIBYTE.equals(varType)) {
         Registers.RegisterZpMem registerZpTribyte =
               new Registers.RegisterZpMem(allocateZp(3), 3);
         return registerZpTribyte;
      } else if(SymbolType.DWORD.equals(varType)) {
         Registers.RegisterZpMem registerZpDWord =
               new Registers.RegisterZpMem(allocateZp(4), 4);
         return registerZpDWord;
      } else if(SymbolType.SDWORD.equals(varType)) {
         Registers.RegisterZpMem registerZpDWord =
               new Registers.RegisterZpMem(allocateZp(4), 4);
         return registerZpDWord;
      } else if(varType.equals(SymbolType.BOOLEAN)) {
         return new Registers.RegisterZpMem(allocateZp(1), 1);
      } else if(varType.equals(SymbolType.VOID)) {
         // No need to setRegister register for VOID value
         return null;
      } else if(varType instanceof SymbolTypePointer) {
         Registers.RegisterZpMem registerZpWord =
               new Registers.RegisterZpMem(allocateZp(2), 2);
         return registerZpWord;
      } else if(varType instanceof SymbolTypeStruct) {
         Registers.RegisterZpMem registerZpStruct =
               new Registers.RegisterZpMem(allocateZp(varType.getSizeBytes()), varType.getSizeBytes());
         return registerZpStruct;
      } else {
         throw new RuntimeException("Unhandled variable type " + varType);
      }
   }


}
