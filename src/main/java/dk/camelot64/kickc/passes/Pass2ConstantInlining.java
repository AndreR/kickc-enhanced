package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.iterator.ProgramValue;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.symbols.*;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.passes.utils.AliasReplacer;

import java.util.*;

/**
 * Compiler Pass consolidating unnamed constants and constant aliasses into the place using them (instructions or the definition of another constant value).
 * <ul>
 * <li> Unnamed constant:   $1 = VIC+$20 </li>
 * <li> Constant alias:     i#1 = i#0    </li>
 * </ul>
 */
public class Pass2ConstantInlining extends Pass2SsaOptimization {

   public Pass2ConstantInlining(Program program) {
      super(program);
   }

   /**
    * Consolidate unnamed constants into other constants value
    *
    * @return true optimization was performed. false if no optimization was possible.
    */
   @Override
   public boolean step() {
      Map<ConstantRef, ConstantValue> inline = new LinkedHashMap<>();
      inline.putAll(findAliasConstants());
      inline.putAll(findUnnamedConstants());
      inline.putAll(findConstVarVersions());
      inline.putAll(findCrossScopeScalarConstants(inline));

      // Remove all string constants
      List<ConstantRef> refs = new ArrayList(inline.keySet());
      for(ConstantRef constantRef : refs) {
         ConstantValue constantValue = inline.get(constantRef);
         if(constantValue instanceof ConstantString) {
            inline.remove(constantRef);
         }
      }
      // Remove all SIZEOF_ constants
      for(ConstantRef constantRef : (List<ConstantRef>) new ArrayList(inline.keySet())) {
         if(constantRef.getLocalName().startsWith("SIZEOF_")) {
            inline.remove(constantRef);
         }
      }

      if(!inline.isEmpty()) {
         // Perform alias replacement within the constant values inside the aliases
         replaceInValues(inline);
         // Replace all usages of the constants in the control flow graph or symbol table
         replaceVariables(inline);
         // Remove parameters
         final Set<ConstantRef> inlineRefs = inline.keySet();
         removeParameters(inlineRefs);
         // Remove from symbol table
         deleteSymbols(getProgramScope(), inlineRefs);

         for(ConstantRef constantRef : inlineRefs) {
            getLog().append("Constant inlined " + constantRef.toString() + " = " + inline.get(constantRef).toString(getProgram()));
         }
      }

      return inline.size() > 0;

   }

   private void removeParameters(Set<ConstantRef> inlineRefs) {
      for(ConstantRef inlineRef : inlineRefs) {
         final Scope scope = getProgramScope().getConstant(inlineRef).getScope();
         final Procedure procedure = scope.getContainingProcedure();
         if(procedure!=null) {
            final List<Variable> parameters = procedure.getParameters();
            final boolean modified = parameters.removeIf(param -> param.getRef().equals(inlineRef));
            if(modified) {
               procedure.setParameters(parameters);
               getLog().append("Parameter inlined " + inlineRef.toString());
            }
         }
      }
   }

   /**
    * Replace any aliases within the constant values themselves
    *
    * @param inline The replacements
    */
   private void replaceInValues(Map<ConstantRef, ConstantValue> inline) {
      boolean replaced = true;
      while(replaced) {
         replaced = false;
         for(ConstantRef constantRef : inline.keySet()) {
            ConstantValue constantValue = inline.get(constantRef);
            ProgramValue.GenericValue genericValue = new ProgramValue.GenericValue(constantValue);
            AliasReplacer replacer = new AliasReplacer(inline);
            ProgramValueIterator.execute(genericValue, replacer, null, null, null);
            if(replacer.isReplaced()) {
               inline.put(constantRef, (ConstantValue) genericValue.get());
            }
         }
      }
   }

   /**
    * Find all unnamed constants $1 = VIC+$20
    *
    * @return Map from constant name to constant value
    */
   private Map<ConstantRef, ConstantValue> findUnnamedConstants() {
      Map<ConstantRef, ConstantValue> unnamed = new HashMap<>();
      Collection<Variable> allConstants = getProgram().getScope().getAllConstants(true);
      for(Variable constant : allConstants) {
         if(constant.getRef().isIntermediate()) {
            if(!(constant.getInitValue() instanceof ConstantString) && !(constant.getInitValue() instanceof ConstantArray) && !(constant.getInitValue() instanceof ConstantStructValue) && !(constant.getInitValue() instanceof StructZero)) {
               unnamed.put(constant.getConstantRef(), constant.getInitValue());
            }
         }
      }
      return unnamed;
   }

   /**
    * Find all constant aliases. An alias is a constant with the exact same value as another constant eg. i#2 = i#0
    *
    * @return Map from constant name to constant value
    */
   private Map<ConstantRef, ConstantValue> findAliasConstants() {
      Map<ConstantRef, ConstantValue> aliases = new HashMap<>();
      ProgramScope programScope = getProgram().getScope();
      Collection<Variable> allConstants = programScope.getAllConstants(true);
      for(Variable constant : allConstants) {
         ConstantValue constantValue = constant.getInitValue();
         if(constantValue instanceof ConstantRef) {
            if(((ConstantRef) constantValue).isIntermediate()) {
               // The value is an intermediate constant - replace all uses of the intermediate with uses of the referrer instead.
               aliases.put((ConstantRef) constant.getInitValue(), constant.getConstantRef());
               final ConstantValue initValue = programScope.getConstant((ConstantRef) constantValue).getInitValue();
               constant.setInitValue(initValue);
            } else {
               aliases.put(constant.getConstantRef(), constant.getInitValue());
            }
         }
      }
      return aliases;
   }

   /**
    * Find
    * - variable versions that are constant, while other versions are still variable eg. i#0 = 0, i#1 = i#0 + 1
    * - constant versions where other constant versions have different values eg. line(1); line(2); (the constants here are the parameters to the method line)
    *
    * @return Map from constant name to constant value
    */
   private Map<ConstantRef, ConstantValue> findConstVarVersions() {
      Map<ConstantRef, ConstantValue> aliases = new HashMap<>();

      Collection<Variable> allConstants = getProgram().getScope().getAllConstants(true);
      Map<Scope, Map<String, List<Symbol>>> scopeBaseSymbols = new HashMap<>();
      for(Variable constant : allConstants) {
         if(constant.getRef().isVersion()) {
            Scope scope = constant.getScope();
            Map<String, List<Symbol>> baseMap = scopeBaseSymbols.get(scope);
            if(baseMap == null) {
               baseMap = new HashMap<>();
               for(Symbol symbol : scope.getAllSymbols()) {
                  if(symbol.getRef().isVersion()) {
                     baseMap.computeIfAbsent(symbol.getRef().getFullNameUnversioned(), k -> new ArrayList<>()).add(symbol);
                  }
               }
               scopeBaseSymbols.put(scope, baseMap);
            }
            String baseName = constant.getRef().getFullNameUnversioned();
            List<Symbol> siblings = baseMap.get(baseName);
            if(siblings != null) {
               for(Symbol symbol : siblings) {
                  ConstantValue value = constant.getInitValue();
                  if(symbol instanceof Variable && ((Variable) symbol).isVariable()) {
                     aliases.put(constant.getConstantRef(), value);
                     getLog().append("Inlining constant with var siblings " + constant);
                     break;
                  } else if(symbol instanceof Variable && ((Variable) symbol).isKindConstant()) {
                     ConstantValue otherValue = ((Variable) symbol).getInitValue();
                     if(!otherValue.equals(value) && !(value instanceof ConstantString) && !(value instanceof ConstantArray) && !(otherValue instanceof ConstantRef)) {
                        aliases.put(constant.getConstantRef(), value);
                        getLog().append("Inlining constant with different constant siblings " + constant);
                        break;
                     }
                  }
               }
            }
         }
      }
      return aliases;
   }

   /**
    * Find named scalar constants (integer/boolean) referenced across procedure/scope boundaries
    * whose address is never taken and that are not referenced by inline KickAsm/ASM blocks.
    *
    * @param existingInline Constants already queued for inlining
    * @return Map from constant reference to its literal value
    */
   private Map<ConstantRef, ConstantValue> findCrossScopeScalarConstants(Map<ConstantRef, ConstantValue> existingInline) {
      ProgramScope programScope = getProgramScope();
      Set<ConstantRef> protectedConsts = new HashSet<>();
      Map<ConstantRef, Set<ScopeRef>> refScopes = new HashMap<>();

      for(Graph.Block block : getGraph().getAllBlocks()) {
         ScopeRef blockScope = block.getScope();
         ProgramValueIterator.execute(block, (programValue, currentStmt, stmtIt, currentBlock) -> {
            Value value = programValue.get();
            if(value instanceof ConstantSymbolPointer csp) {
               if(csp.getToSymbol() instanceof ConstantRef cr) {
                  protectedConsts.add(cr);
               }
            } else if(value instanceof ConstantRef cr) {
               if(programValue instanceof ProgramValue.KickAsmUses
                     || programValue instanceof ProgramValue.ProgramValueConstantArrayKickAsmUses
                     || programValue instanceof ProgramValue.ProgramValueAsmReferenced) {
                  protectedConsts.add(cr);
               }
               refScopes.computeIfAbsent(cr, k -> new HashSet<>()).add(blockScope);
            }
         });
      }

      for(Variable var : programScope.getAllVars(true)) {
         ScopeRef varScope = var.getScope() != null ? var.getScope().getRef() : ScopeRef.ROOT;
         ProgramValueIterator.execute(var, (programValue, currentStmt, stmtIt, currentBlock) -> {
            Value value = programValue.get();
            if(value instanceof ConstantSymbolPointer csp) {
               if(csp.getToSymbol() instanceof ConstantRef cr) {
                  protectedConsts.add(cr);
               }
            } else if(value instanceof ConstantRef cr) {
               if(programValue instanceof ProgramValue.KickAsmUses
                     || programValue instanceof ProgramValue.ProgramValueConstantArrayKickAsmUses
                     || programValue instanceof ProgramValue.ProgramValueAsmReferenced) {
                  protectedConsts.add(cr);
               }
               refScopes.computeIfAbsent(cr, k -> new HashSet<>()).add(varScope);
            }
         });
      }

      Map<ConstantRef, ConstantValue> result = new LinkedHashMap<>();
      for(Variable constant : programScope.getAllConstants(true)) {
         ConstantRef ref = constant.getConstantRef();
         if(existingInline.containsKey(ref) || protectedConsts.contains(ref)) {
            continue;
         }
         String localName = ref.getLocalName();
         if(localName.startsWith("SIZEOF_") || localName.startsWith("OFFSET_STRUCT_")) {
            continue;
         }
         if(constant.isVolatile() || constant.isExport()) {
            continue;
         }
         if(constant.getMemoryAddress() != null || (constant.getRegister() != null && constant.getRegister().isAddressHardcoded())) {
            continue;
         }
         Scope scope = constant.getScope();
         if(scope == null || scope instanceof EnumDefinition || scope instanceof StructDefinition) {
            continue;
         }
         SymbolType type = constant.getType();
         if(!(type instanceof SymbolTypeIntegerFixed || SymbolType.BOOLEAN.equals(type))) {
            continue;
         }
         ConstantValue initVal = constant.getInitValue();
         if(initVal == null
               || initVal instanceof ConstantString
               || initVal instanceof ConstantArray
               || initVal instanceof ConstantStructValue
               || initVal instanceof StructZero
               || initVal instanceof ConstantSymbolPointer
               || initVal instanceof ConstantPointer) {
            continue;
         }
         ConstantLiteral literal;
         try {
            literal = initVal.calculateLiteral(programScope);
         } catch(RuntimeException e) {
            continue;
         }
         if(!(literal instanceof ConstantInteger || literal instanceof ConstantBool || literal instanceof ConstantChar)) {
            continue;
         }
         ConstantValue replacement = literal;
         if(literal instanceof ConstantInteger ci && type instanceof SymbolTypeIntegerFixed) {
            SymbolType litType = ci.getType();
            if(litType == null || SymbolType.NUMBER.equals(litType) || SymbolType.UNUMBER.equals(litType) || SymbolType.SNUMBER.equals(litType)) {
               replacement = new ConstantInteger(ci.getInteger(), type.getQualified(false, false));
            }
         }
         Set<ScopeRef> scopes = refScopes.get(ref);
         if(scopes == null || scopes.isEmpty()) {
            continue;
         }
         ScopeRef ownerScope = scope.getRef();
         boolean crossScope = false;
         for(ScopeRef s : scopes) {
            if(!ownerScope.equals(s)) {
               crossScope = true;
               break;
            }
         }
         if(!crossScope) {
            continue;
         }
         result.put(ref, replacement);
      }
      return result;
   }

}
