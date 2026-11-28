/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is dual-licensed under either the MIT license found in the
 * LICENSE-MIT file in the root directory of this source tree or the Apache
 * License, Version 2.0 found in the LICENSE-APACHE file in the root directory
 * of this source tree. You may select, at your option, one of the
 * above-listed licenses.
 */

@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package com.facebook.buck.jvm.kotlin.compilerplugins.common

import com.facebook.kotlin.compilerplugins.common.isOwnDeclaration
import com.facebook.kotlin.compilerplugins.common.isSameModuleJava
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvedDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirErrorExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.AbbreviatedTypeAttribute
import org.jetbrains.kotlin.fir.types.ConeCapturedType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeIntegerLiteralType
import org.jetbrains.kotlin.fir.types.ConeIntersectionType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirErrorTypeRef
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

private object EmptiedBodyHadLocalClassesKey : FirDeclarationDataKey()

/**
 * Set on a declaration whose unresolved body the ABI pipeline emptied when that body had local
 * classes (see [hasLocalClassShapes]), which keepability can no longer see in the body itself.
 */
var FirDeclaration.emptiedBodyHadLocalClasses: Boolean? by
    FirDeclarationDataRegistry.data(EmptiedBodyHadLocalClassesKey)

/**
 * Whether [element] declares something the JVM backend numbers as a local class of its enclosing
 * declaration (`Outer$name$1`): an anonymous object, a lambda (inlined and invokedynamic ones take
 * a number too), a callable reference or a local class, including in a body emptied before body
 * resolution. Works on raw and on resolved FIR.
 */
fun hasLocalClassShapes(element: FirElement): Boolean {
  var found = false
  element.accept(
      object : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
          if (found) return
          if (
              element is FirAnonymousFunction ||
                  element is FirAnonymousObject ||
                  element is FirCallableReferenceAccess ||
                  (element is FirRegularClass && element.symbol.classId.isLocal) ||
                  (element is FirDeclaration && element.emptiedBodyHadLocalClasses == true)
          ) {
            found = true
            return
          }
          element.acceptChildren(this)
        }
      },
  )
  return found
}

/**
 * Decides whether the body of a non-private inline function can be kept in the source-only ABI.
 *
 * Every declaration the function's signature and body reference (calls, types, implicit receivers
 * and type arguments) must be declared in this module's own Kotlin sources or be a library
 * declaration that [isLibraryDeclarationAllowed] accepts; unresolved references, and declarations
 * of Kosabi stub files, never qualify. With [lowerableOnly], the body must also have a shape that
 * k2-jvm-abi-gen can emit as a plain method body: see [hasLowerableShape].
 *
 * @param isLibraryDeclarationAllowed called with the fully qualified name of a library class, or of
 *   a top-level library function, property or typealias (then `isTopLevelMember` is true).
 * @param isStubFile true for the Kosabi stub files of the ABI compilation.
 */
class InlineBodyKeepability(
    private val session: FirSession,
    private val lowerableOnly: Boolean,
    private val isStubFile: (FirFile) -> Boolean = { false },
    private val isLibraryDeclarationAllowed: (fqName: String, isTopLevelMember: Boolean) -> Boolean,
    // Annotations named as triggers in this compilation's plugin options
    // (allopen/noarg/sam-with-receiver `annotation=` values, preset expansions):
    // rejected even when their package is allowlisted.
    private val pluginTriggerAnnotations: Set<String> = emptySet(),
) {
  private val verdicts = HashMap<FirFunction, Boolean>()
  private val rejectDetails = HashMap<FirFunction, String>()
  private val inProgress = HashSet<FirFunction>()

  /** The functions the applicability check reports as inline functions. */
  fun isCandidate(function: FirFunction): Boolean {
    if (function.symbol is FirNamedFunctionSymbol) {
      return function.isInline && !function.effectiveVisibility.privateApi
    }
    // Property accessors carry no name; the inline flag lives on the accessors themselves
    // (an `inline var` with explicit accessors has isInline=false on the property).
    if (function is FirPropertyAccessor) {
      return function.isInline && !function.effectiveVisibility.privateApi
    }
    return false
  }

  fun isKeepable(function: FirFunction): Boolean {
    verdicts[function]?.let {
      return it
    }
    // A re-entrant query runs through the local-class numbering check: the check queries an
    // earlier same-named declaration whose own evaluation calls back into this function (an
    // overload that delegates to this one, for example). Calls alone cannot cycle, since the
    // compiler rejects recursive inline functions.
    // With lowerableOnly, answer optimistically. If the earlier declaration is genuinely keepable,
    // its body reaches the ABI and this function's classes number correctly; if it is not, abi-gen
    // reports the earlier declaration as its own error, failing the build before a misnumbered
    // body ships. Without lowerableOnly (applicability) no numbering check runs, so a cycle strips
    // as before.
    if (!inProgress.add(function)) return lowerableOnly
    val result =
        try {
          compute(function)
        } finally {
          inProgress.remove(function)
        }
    verdicts[function] = result
    return result
  }

  private fun compute(function: FirFunction): Boolean {
    if (!isCandidate(function) || function.body == null) return false
    if (lowerableOnly) {
      shapeRejectReason(function)?.let {
        rejectDetails[function] = it
        return false
      }
    }
    val visitor = Visitor(function)
    function.accept(visitor)
    visitor.rejectDetail?.let { rejectDetails[function] = it }
    if (visitor.keepable && lowerableOnly) {
      localClassNumberingRejectReason(function)?.let {
        rejectDetails[function] = it
        return false
      }
    }
    return visitor.keepable
  }

  /**
   * The JVM backend numbers the classes of a function's anonymous objects, lambdas and callable
   * references (`Outer$name$1`) with one counter per enclosing name, in declaration order, and an
   * inlined or invokedynamic lambda takes a number too. A same-named declaration earlier in the
   * same container therefore takes the first numbers; its body reaches the ABI only when it is a
   * kept inline function, so otherwise the kept body's classes would be numbered from 1 instead of
   * matching the classes its callers' full compile names. A body without classes of its own (see
   * [hasOwnLocalClasses]) names no numbered class, so nothing before it matters. Shapes living in a
   * called inline body are invisible here; [transitiveNumberingDonors] finds them for the IR-time
   * reconstruct-or-reject decision instead of rejecting eagerly.
   */
  private fun localClassNumberingRejectReason(function: FirFunction): String? {
    val name = (function.symbol as? FirNamedFunctionSymbol)?.name ?: return null
    if (!hasOwnLocalClasses(function)) return null
    for (declaration in containerDeclarations(function)) {
      if (declaration === function) return null
      val sameName =
          when (declaration) {
            is FirFunction -> (declaration.symbol as? FirNamedFunctionSymbol)?.name == name
            is FirProperty -> declaration.name == name
            else -> false
          }
      if (!sameName) continue
      if (declaration is FirFunction && declaration.isInline && isKeepable(declaration)) continue
      if (hasLocalClassShapes(declaration))
          return "not lowerable: an earlier `$name` takes the first numbers of its local classes"
    }
    return null
  }

  /**
   * Earlier same-named declarations whose inlined content consumes this name's numbers. Each match
   * calls an in-module inline function whose body carries local-class shapes (see
   * [hasLocalClassShapes]), following the chain through shapeless inline middles, or reads a
   * property whose inline accessor body carries them. The shapes are invisible in the declaration
   * itself, but once inlined at IR time they shift a later same-named kept body. A bodyless
   * (library) inline callee ends nothing unless it is reified or an accessor: reified operations
   * regenerate per instantiation at the call site, and inline accessors inline there too.
   *
   * A candidate with no numbering needs of its own — no local classes (see [hasOwnLocalClasses])
   * and no transitive chains through in-module inline callees — emits no local classes, so a
   * purely-direct earlier donor (one that calls no in-module shaped body itself) cannot shift it:
   * such donors are dropped from the scan. (A same-named non-inline donor's own classes are its
   * implementation detail; they never inline into the candidate.) Donors with transitive content
   * still lower real: the reservation keeps inlined-lambda marker numbering exact.
   */
  fun transitiveNumberingDonors(function: FirFunction): NumberingDonorScan {
    val name = (function.symbol as? FirNamedFunctionSymbol)?.name ?: return NumberingDonorScan.None
    val consumerNeedsNumbering =
        hasOwnLocalClasses(function) || collectNumberingChains(function.body).isNotEmpty()
    val donors = mutableListOf<TransitiveDonor>()
    for (declaration in containerDeclarations(function)) {
      if (declaration === function) break
      val sameName =
          when (declaration) {
            is FirFunction -> (declaration.symbol as? FirNamedFunctionSymbol)?.name == name
            is FirProperty -> declaration.name == name
            else -> false
          }
      if (!sameName) continue
      if (declaration is FirFunction && declaration.isInline && isKeepable(declaration)) continue
      if (declaration is FirFunction) {
        // An earlier declaration with local-class shapes of its own consumes this name's numbers
        // just as inlined content does: the donor lowers real either way. The eager check already
        // rejects the combination when the consumer has classes of its own; without them the
        // consumer defers here instead of numbering silently from 1.
        val chains = collectNumberingChains(declaration.body)
        if (!hasLocalClassShapes(declaration) && chains.isEmpty()) continue
        val flat = chains.flatten()
        donors +=
            TransitiveDonor(
                donor = declaration,
                callees = flat.map { it.symbol }.distinct(),
                terminals = flat.filter { it.body == null }.map { it.symbol }.distinct(),
                reason =
                    if (chains.isEmpty()) {
                      "not lowerable: an earlier `$name` declares local classes, which take " +
                          "the first numbers"
                    } else {
                      "not lowerable: an earlier `$name` inlines " +
                          chains.joinToString("; ") { chain ->
                            chain.joinToString(" → ") { it.display }
                          } +
                          ", whose local classes take the first numbers"
                    },
                donorDesc =
                    if (
                        hasMangledMemberReturn(declaration) || hasNestedNamedFunction(declaration)
                    ) {
                      null
                    } else {
                      jvmDescriptor(declaration)
                    },
            )
        continue
      }
      // A same-named property rejects only when it could carry numbering content: its delegate,
      // initializer or accessors hold local-class shapes (or did before emptying, via the
      // emptied-body marker). A provably-shapeless declaration (e.g. `const val compute = 1`)
      // consumes no numbers. Same-container properties are always local, so their bodies or
      // markers are visible here.
      if (
          declaration is FirProperty && hasLocalClassShapes(declaration) && consumerNeedsNumbering
      ) {
        return NumberingDonorScan.Unmappable(
            "not lowerable: an earlier `$name` is not a function, so its inlined content " +
                "cannot be reconstructed",
        )
      }
    }
    if (!consumerNeedsNumbering && donors.all { it.callees.isEmpty() }) {
      // A candidate that emits no local classes needs no reservation: purely-direct donors'
      // classes never reach it.
      return NumberingDonorScan.None
    }
    return NumberingDonorScan.Found(donors)
  }

  /** The numbering-donor scan for one kept function: none, found, or never reconstructible. */
  sealed interface NumberingDonorScan {
    data object None : NumberingDonorScan

    data class Found(val donors: List<TransitiveDonor>) : NumberingDonorScan

    data class Unmappable(val reason: String) : NumberingDonorScan
  }

  /** An earlier same-named declaration calling inlinable bodies, with its consumer. */
  data class TransitiveDonor(
      /** The earlier declaration whose IR-time inlined content consumes the name's numbers. */
      val donor: FirFunction,
      /**
       * Every inline symbol on the consumption paths, direct first: the chains from the donor to
       * each shaped body. All must be kept for the content to be reconstructed.
       */
      val callees: List<FirCallableSymbol<*>>,
      /**
       * The bodyless symbols among [callees] (library reified functions, library inline accessors,
       * and bodyless callees with crossinline or suspend parameters such as `Sequence { }`): their
       * content regenerates from the library at the call site in every compilation, so the restore
       * gate does not require them kept.
       */
      val terminals: List<FirCallableSymbol<*>>,
      /** The keep-rejection text if the content cannot be reconstructed. */
      val reason: String,
      /**
       * The donor's JVM method descriptor, or null when it cannot be established exactly:
       * unmappable signature shapes (context receivers, type variables, literal types, local
       * classes, non-null value classes, suspend-function types), name-mangling parameter or
       * member-return types (unsigned and value classes, nullable or not), or a named local
       * function declared in the donor body (its private static helper cannot be named exactly).
       * Null reconstructs nothing: the consumer is rejected instead.
       */
      val donorDesc: String?,
  )

  /** One inline step on a consumption path: the symbol that must be kept, with its body. */
  private data class InlineCallee(
      val symbol: FirCallableSymbol<*>,
      val body: FirElement?,
      val display: String,
  )

  /**
   * Every chain of in-module inline callees from [body] to a shaped body, direct first. A call to
   * an inline function with shapes ends the chain; a shapeless inline callee continues through its
   * body (it inlines into the donor, and the shaped body behind it inlines further). A reified
   * library callee, or a library inline accessor, with no FIR body here ends the chain as a
   * terminal entry: it regenerates at the call site. Reads of properties with shaped inline
   * accessors match the same way. Non-inline and non-reified body-less function callees end
   * nothing: their classes are numbered where they are declared, not at the call site. Each direct
   * callee is followed with a fresh visited set, so chains that share a callee behind different
   * middles are each recorded.
   */
  private fun collectNumberingChains(body: FirElement?): List<List<InlineCallee>> {
    if (body == null) return emptyList()
    val roots = mutableListOf<InlineCallee>()
    body.accept(
        object : FirVisitorVoid() {
          override fun visitElement(element: FirElement) {
            roots += inlineCallees(element)
            element.acceptChildren(this)
          }
        },
    )
    return roots
        .flatMap { followNumberingChain(it, emptyList(), HashSet<FirCallableSymbol<*>>()) }
        .distinct()
  }

  private fun followNumberingChain(
      callee: InlineCallee,
      chain: List<InlineCallee>,
      visited: Set<FirCallableSymbol<*>>,
  ): List<List<InlineCallee>> {
    if (callee.symbol in visited) return emptyList()
    val next = chain + callee
    if (callee.body == null) return listOf(next)
    if (hasLocalClassShapes(callee.body)) return listOf(next)
    val found = mutableListOf<List<InlineCallee>>()
    callee.body.accept(
        object : FirVisitorVoid() {
          override fun visitElement(element: FirElement) {
            for (nested in inlineCallees(element)) {
              found += followNumberingChain(nested, next, visited + callee.symbol)
            }
            element.acceptChildren(this)
          }
        },
    )
    return found
  }

  /**
   * The in-module inline callees of one expression: called functions, and the inline accessors of
   * read or written properties. Each carries the body that inlines and the name the rejection
   * reports.
   */
  private fun inlineCallees(element: FirElement): List<InlineCallee> {
    if (element is FirFunctionCall) {
      val callee =
          (element.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
              as? FirNamedFunctionSymbol ?: return emptyList()
      val fir = callee.fir as? FirFunction ?: return emptyList()
      if (!fir.isInline) return emptyList()
      val body = fir.body
      if (body == null) {
        // A bodyless (library) inline callee regenerates nothing unless it is reified or it must
        // defer a lambda: a reified operation regenerates per instantiation at the call site, while
        // a crossinline or suspend parameter is boxed into a call-site class (e.g. stdlib
        // `Sequence { }` wraps its iterator in an anonymous object), consuming its numbers.
        val regenerates =
            fir.typeParameters.any { it.symbol.fir.isReified } ||
                fir.valueParameters.any { param ->
                  param.isCrossinline ||
                      (param.returnTypeRef.coneTypeOrNull as? ConeClassLikeType)
                          ?.lookupTag
                          ?.classId
                          ?.shortClassName
                          ?.asString()
                          ?.startsWith("SuspendFunction") == true
                }
        if (!regenerates) return emptyList()
        return listOf(InlineCallee(callee, null, "`${callee.callableId}`"))
      }
      return listOf(InlineCallee(callee, body, "`${callee.callableId}`"))
    }
    if (element is FirPropertyAccessExpression) {
      val property =
          (element.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
              as? FirPropertySymbol ?: return emptyList()
      val prop = property.callableId?.toString() ?: return emptyList()
      return listOfNotNull(property.getterSymbol, property.setterSymbol).mapNotNull { accessor ->
        val fir = accessor.fir as? FirPropertyAccessor ?: return@mapNotNull null
        if (!fir.isInline) return@mapNotNull null
        val display = "`$prop${if (accessor === property.getterSymbol) ".get" else ".set"}`"
        val body = fir.body
        if (body == null) {
          // A bodyless (library) inline accessor regenerates at the call site like a reified
          // terminal: record it; the restore gate exempts it the same way.
          return@mapNotNull InlineCallee(accessor, null, display)
        }
        InlineCallee(accessor, body, display)
      }
    }
    return emptyList()
  }

  /**
   * The JVM method descriptor for [function]'s erased signature, or null when it cannot be
   * established exactly. The extension receiver, if any, leads the parameters, as the backend
   * passes it; a suspend function gains the trailing continuation parameter the backend adds.
   * Nullable primitives box; most classes map by package. No descriptor form exists for context
   * parameters, type variables, literal types, local classes, non-null value classes and
   * suspend-function types: all fall back to null (fail closed). Unsigned and value-class
   * parameters are rejected before mapping (see [hasMangledParamType]).
   */
  private fun jvmDescriptor(function: FirFunction): String? {
    // Context parameters are not mapped: fail closed until their order is proven.
    if (function.contextParameters.isNotEmpty()) return null
    // An unsigned or value-class parameter mangles the JVM name with an unpredictable hash.
    if (hasMangledParamType(function)) return null
    val receivers =
        function.receiverParameter?.let {
          listOf(it.typeRef.coneTypeOrNull?.let(::jvmTypeDescriptor) ?: return null)
        } ?: emptyList()
    val params =
        function.valueParameters.map { param ->
          val typeRef = param.returnTypeRef.coneTypeOrNull ?: return null
          if (!param.isVararg) {
            jvmTypeDescriptor(typeRef) ?: return null
          } else {
            // A vararg erases to an array of the component's erased type: primitives stay
            // primitive, unlike the boxed components of a generic `Array`. FIR stores a
            // primitive vararg directly as its primitive array (`vararg xs: Int` is `IntArray`,
            // seen in the ABI compile itself) and any other vararg in the wrapped `Array<out T>`
            // form, whose `out` projection marks the component.
            val normalized =
                when (typeRef) {
                  is ConeFlexibleType -> typeRef.lowerBound
                  is ConeDefinitelyNotNullType -> typeRef.original
                  else -> typeRef
                }
            val arrayType =
                (normalized as? ConeClassLikeType)?.takeIf {
                  it.lookupTag.classId.shortClassName.asString() == "Array"
                }
            if (arrayType != null) {
              val arrayArg =
                  arrayType.typeArguments.singleOrNull() as? ConeKotlinTypeProjection ?: return null
              // Only an `out` projection is the wrapped form; an invariant `Array` is the
              // element itself (possibly an array, which boxes its own components). Wrapped
              // components erase in generic-argument mode (`vararg Array<UIntArray>` is
              // `UIntArray[]`, ground truth by javap).
              if (arrayArg.kind == ProjectionKind.OUT) {
                "[" + (jvmTypeDescriptorInner(arrayArg.type, false, true, true) ?: return null)
              } else {
                "[" + (jvmTypeDescriptor(normalized) ?: return null)
              }
            } else {
              // Already an array (`IntArray` and friends): its descriptor is exact as is.
              jvmTypeDescriptor(normalized) ?: return null
            }
          }
        }
    // A suspend function returns `Object` at the JVM level however it is declared.
    val returns =
        if (function.isSuspend) {
          "Ljava/lang/Object;"
        } else {
          function.returnTypeRef.coneTypeOrNull?.let { jvmTypeDescriptor(it, isReturn = true) }
              ?: return null
        }
    val suspension = if (function.isSuspend) "Lkotlin/coroutines/Continuation;" else ""
    return (receivers + params).joinToString(
        separator = "",
        prefix = "(",
        postfix = "$suspension)$returns",
    )
  }

  /**
   * Whether any value parameter (or the receiver, which the backend passes first) would mangle the
   * JVM name: unsigned and value-class parameter types gain an unpredictable hash suffix, so no
   * stub key can name them. Member value-class returns mangle too; see [hasMangledMemberReturn].
   */
  private fun hasMangledParamType(function: FirFunction): Boolean {
    val params =
        listOfNotNull(function.receiverParameter?.typeRef?.coneTypeOrNull) +
            function.valueParameters.mapNotNull { it.returnTypeRef.coneTypeOrNull }
    return params.any(::isMangledParamType)
  }

  private fun isMangledParamType(type: ConeKotlinType): Boolean =
      when (type) {
        is ConeFlexibleType -> isMangledParamType(type.lowerBound)
        is ConeDefinitelyNotNullType -> isMangledParamType(type.original)
        is ConeClassLikeType -> {
          // Nullable or not: an unsigned or value-class parameter mangles the JVM name with an
          // unpredictable hash (`a1-ExVfyTY(kotlin.UInt)`, ground truth by javap), so no stub key
          // can name it. A direct unsigned-array parameter keeps its plain name
          // (`ua(kotlin.UIntArray[])`), so unsigned arrays never flag parameters.
          val classId = type.lookupTag.classId
          if (
              classId.packageFqName.asString() == "kotlin" &&
                  classId.shortClassName.asString() in UNSIGNED_ARRAYS
          ) {
            return false
          }
          isMangledClassifier(classId)
        }
        else -> false
      }

  /**
   * Whether [classId] can mangle a JVM name: unsigned types, unsigned arrays and value classes do,
   * nullable or not (`a1-ExVfyTY`, `a2-v1W5SeY`, member `ta-g_c56RQ`, all ground truth by javap).
   * Value classes mangle from any package: user value classes behave exactly like `kotlin.UInt`, so
   * the inline-or-value check runs before the `kotlin` package gate.
   */
  private fun isMangledClassifier(classId: ClassId): Boolean {
    if (isInlineOrValueClass(classId)) return true
    if (classId.packageFqName.asString() != "kotlin") return false
    val short = classId.shortClassName.asString()
    return short in UNSIGNED || short in UNSIGNED_ARRAYS
  }

  /**
   * Whether [function]'s declared return type mangles a MEMBER's JVM name. Top-level functions
   * never mangle for the return type (`f1`, `f2` plain by javap); members do (`t1-OGnWXxg`,
   * `t2-gbq4QnA`, `t3-hIQOoDM`, `t4-iAqF9CU`, `ta-g_c56RQ`). Only the top-level classifier counts
   * (`List<UInt>` member returns stay plain).
   */
  private fun hasMangledMemberReturn(function: FirFunction): Boolean {
    if (function.symbol.callableId?.classId == null) return false
    val returnType = function.returnTypeRef.coneTypeOrNull ?: return false
    val normalized =
        when (returnType) {
          is ConeFlexibleType -> returnType.lowerBound
          is ConeDefinitelyNotNullType -> returnType.original
          else -> returnType
        }
    val classId = (normalized as? ConeClassLikeType)?.lookupTag?.classId ?: return false
    return isMangledClassifier(classId)
  }

  /**
   * Whether [function]'s body declares a named local function: it compiles to a private static
   * helper (`nested$inner`, ground truth by javap) that the stubber cannot name exactly, so such
   * donors fail closed. Lambdas and anonymous objects generate numbered classes instead, which the
   * numbering side effect reproduces exactly.
   */
  private fun hasNestedNamedFunction(function: FirFunction): Boolean {
    val body = function.body ?: return false
    var found = false
    body.accept(
        object : FirVisitorVoid() {
          override fun visitElement(element: FirElement) {
            if (found) return
            if (element is FirFunction && element !is FirAnonymousFunction) {
              found = true
              return
            }
            element.acceptChildren(this)
          }
        },
    )
    return found
  }

  private val UNSIGNED = setOf("UByte", "UShort", "UInt", "ULong")

  private val UNSIGNED_ARRAYS = setOf("UByteArray", "UShortArray", "UIntArray", "ULongArray")

  private fun jvmTypeDescriptor(type: ConeKotlinType, isReturn: Boolean = false): String? =
      jvmTypeDescriptorInner(type, isReturn, type.isMarkedNullable, false)

  private fun jvmTypeDescriptorInner(
      type: ConeKotlinType,
      isReturn: Boolean,
      nullable: Boolean,
      isArrayComponent: Boolean,
  ): String? =
      when (type) {
        is ConeFlexibleType ->
            jvmTypeDescriptorInner(type.lowerBound, isReturn, nullable, isArrayComponent)
        is ConeDefinitelyNotNullType ->
            jvmTypeDescriptorInner(type.original, isReturn, false, isArrayComponent)
        is ConeErrorType -> null
        is ConeClassLikeType -> {
          val classId = type.lookupTag.classId
          if (classId.isLocal) return null
          val short = classId.shortClassName.asString()
          // Suspend-function values erase by lowering context, not by name.
          if (short.startsWith("SuspendFunction")) return null
          jdkDescriptor(classId)?.let {
            return it
          }
          when (classId.packageFqName.asString()) {
            "kotlin" -> kotlinDescriptor(classId, short, type, isReturn, nullable, isArrayComponent)
            "kotlin.collections" -> kotlinCollectionsDescriptor(classId, short)
            else -> {
              if (isInlineOrValueClass(classId)) {
                // A nullable value class erases to its box, which names it exactly; the
                // underlying type of a non-null one cannot be named here.
                if (!nullable) return null
              }
              val pkg = classId.packageFqName.asString()
              val pkgPrefix = if (pkg.isEmpty()) "" else pkg.replace('.', '/') + "/"
              "L$pkgPrefix${classId.relativeClassName.asString().replace('.', '$')};"
            }
          }
        }
        else -> null
      }

  /**
   * JVM descriptors for the classes the backend maps to the JDK, across the `kotlin` packages that
   * declare them. Collection interfaces live in `kotlin.collections`, the rest in `kotlin`;
   * anything else keeps its Kotlin class. A user class that happens to share a short name is never
   * mapped: the package must match too.
   */
  private fun jdkDescriptor(classId: ClassId): String? {
    val pkg = classId.packageFqName.asString()
    val short = classId.shortClassName.asString()
    val relative = classId.relativeClassName.asString()
    if (
        (pkg == "kotlin" || pkg == "kotlin.collections") &&
            (relative == "Map.Entry" || relative == "MutableMap.MutableEntry")
    ) {
      return "Ljava/util/Map\$Entry;"
    }
    if (pkg == "kotlin" || pkg == "kotlin.collections") {
      when (short) {
        "Collection",
        "MutableCollection" -> return "Ljava/util/Collection;"
        "List",
        "MutableList" -> return "Ljava/util/List;"
        "Set",
        "MutableSet" -> return "Ljava/util/Set;"
        "Map",
        "MutableMap" -> return "Ljava/util/Map;"
        "Iterable",
        "MutableIterable" -> return "Ljava/lang/Iterable;"
        "Iterator",
        "MutableIterator" -> return "Ljava/util/Iterator;"
        "ListIterator",
        "MutableListIterator" -> return "Ljava/util/ListIterator;"
      }
    }
    if (pkg == "kotlin.text" && (short == "StringBuilder" || short == "Appendable")) {
      return if (short == "StringBuilder") "Ljava/lang/StringBuilder;" else "Ljava/lang/Appendable;"
    }
    // Expect/actual classes FIR keeps unexpanded: `Cloneable` and `Int.Companion` (all ground
    // truth by javap). Other companions (`String.Companion`, ...) stay unmapped and fail closed
    // downstream.
    if (pkg == "kotlin" && relative == "Int.Companion") {
      return "Lkotlin/jvm/internal/IntCompanionObject;"
    }
    if (pkg == "kotlin.concurrent.atomics" && short == "AtomicInt") {
      return "Ljava/util/concurrent/atomic/AtomicInteger;"
    }
    if (pkg != "kotlin") return null
    return when (short) {
      "Any" -> "Ljava/lang/Object;"
      "String" -> "Ljava/lang/String;"
      "CharSequence" -> "Ljava/lang/CharSequence;"
      "Cloneable" -> "Ljava/lang/Cloneable;"
      "Comparable" -> "Ljava/lang/Comparable;"
      "Comparator" -> "Ljava/util/Comparator;"
      "Number" -> "Ljava/lang/Number;"
      "Enum" -> "Ljava/lang/Enum;"
      "Annotation" -> "Ljava/lang/annotation/Annotation;"
      "Throwable" -> "Ljava/lang/Throwable;"
      "Exception" -> "Ljava/lang/Exception;"
      "RuntimeException" -> "Ljava/lang/RuntimeException;"
      "Error" -> "Ljava/lang/Error;"
      "Appendable" -> "Ljava/lang/Appendable;"
      "Nothing" -> "Ljava/lang/Void;"
      else -> null
    }
  }

  /**
   * JVM descriptors for the `kotlin` package: primitives (boxed when nullable), arrays, the
   * function interfaces, and the classes the backend maps to the JDK. Anything else is a genuine
   * Kotlin class. `Unit?` stays an object reference; only a non-null `Unit` return is void.
   */
  private fun kotlinDescriptor(
      classId: ClassId,
      short: String,
      type: ConeClassLikeType,
      isReturn: Boolean,
      nullable: Boolean,
      isArrayComponent: Boolean,
  ): String? {
    if (short.startsWith("Function")) {
      return if (short.removePrefix("Function").toIntOrNull() in 0..22) {
        "Lkotlin/jvm/functions/$short;"
      } else {
        null
      }
    }
    if (!nullable) {
      when (short) {
        "Boolean" -> return "Z"
        "Byte" -> return "B"
        "Char" -> return "C"
        "Double" -> return "D"
        "Float" -> return "F"
        "Int" -> return "I"
        "Long" -> return "J"
        "Short" -> return "S"
        "Unit" -> return if (isReturn) "V" else "Lkotlin/Unit;"
        "UByte" -> return "B"
        "UShort" -> return "S"
        "UInt" -> return "I"
        "ULong" -> return "J"
      }
    } else {
      when (short) {
        "Boolean" -> return "Ljava/lang/Boolean;"
        "Byte" -> return "Ljava/lang/Byte;"
        "Char" -> return "Ljava/lang/Character;"
        "Double" -> return "Ljava/lang/Double;"
        "Float" -> return "Ljava/lang/Float;"
        "Int" -> return "Ljava/lang/Integer;"
        "Long" -> return "Ljava/lang/Long;"
        "Short" -> return "Ljava/lang/Short;"
        "UByte" -> return "Lkotlin/UByte;"
        "UShort" -> return "Lkotlin/UShort;"
        "UInt" -> return "Lkotlin/UInt;"
        "ULong" -> return "Lkotlin/ULong;"
        "Unit" -> return "Lkotlin/Unit;"
      }
    }
    if (short == "Array") {
      val argument = type.typeArguments.singleOrNull() ?: return null
      // Erasure keeps `out` components, but an `in` projection or a star erases to `Object`:
      // `Array<in String>` is `Object[]` while `Array<out Int>` is `Integer[]`.
      if (
          argument is ConeStarProjection ||
              (argument as? ConeKotlinTypeProjection)?.kind == ProjectionKind.IN
      ) {
        return "[Ljava/lang/Object;"
      }
      val component = (argument as? ConeKotlinTypeProjection)?.type ?: return null
      // A generic `Array` boxes its components (`Array<Int>` is `Integer[]`, `Array<UInt>` is
      // `UInt[]`); unsigned arrays stay classes (`Array<UIntArray>` is `UIntArray[]`).
      return "[" + (jvmTypeDescriptorInner(component, false, true, true) ?: return null)
    }
    when (short) {
      "BooleanArray" -> return "[Z"
      "ByteArray" -> return "[B"
      "CharArray" -> return "[C"
      "DoubleArray" -> return "[D"
      "FloatArray" -> return "[F"
      "IntArray" -> return "[I"
      "LongArray" -> return "[J"
      "ShortArray" -> return "[S"
      // In a generic component position unsigned arrays stay classes (`Array<UIntArray>` is
      // `UIntArray[]`); directly they erase to primitive arrays (`UIntArray` is `int[]`).
      "UByteArray" -> return if (isArrayComponent) "Lkotlin/UByteArray;" else "[B"
      "UShortArray" -> return if (isArrayComponent) "Lkotlin/UShortArray;" else "[S"
      "UIntArray" -> return if (isArrayComponent) "Lkotlin/UIntArray;" else "[I"
      "ULongArray" -> return if (isArrayComponent) "Lkotlin/ULongArray;" else "[J"
    }
    if (isInlineOrValueClass(classId)) return null
    return "Lkotlin/${classId.relativeClassName.asString().replace('.', '$')};"
  }

  /** The `kotlin.collections` classes the backend maps to the JDK; the rest stay Kotlin classes. */
  private fun kotlinCollectionsDescriptor(classId: ClassId, short: String): String =
      when (short) {
        "ArrayList" -> "Ljava/util/ArrayList;"
        "HashMap" -> "Ljava/util/HashMap;"
        "HashSet" -> "Ljava/util/HashSet;"
        "LinkedHashMap" -> "Ljava/util/LinkedHashMap;"
        "LinkedHashSet" -> "Ljava/util/LinkedHashSet;"
        else -> "Lkotlin/collections/${classId.relativeClassName.asString().replace('.', '$')};"
      }

  private fun isInlineOrValueClass(classId: ClassId): Boolean {
    val fir = session.symbolProvider.getClassLikeSymbolByClassId(classId)?.fir
    return fir is FirRegularClass && fir.isInlineOrValue
  }

  /**
   * Whether the compiled [function] has numbered classes of its own: anonymous objects, callable
   * references, local classes, lambdas that are not inlined, and declarations whose emptied body
   * had local classes. A lambda passed to a parameter of an inline function that is not `noinline`
   * is inlined into the body and gets no class. It still takes a number (see
   * [hasLocalClassShapes]), but the number shows only in the debug name of its marker variable
   * (`$i$a$-all-Outer$name$2`), so it is not counted here.
   */
  private fun hasOwnLocalClasses(function: FirFunction): Boolean {
    val inlinedLambdas = HashSet<FirAnonymousFunction>()
    function.accept(
        object : FirVisitorVoid() {
          override fun visitElement(element: FirElement) {
            if (element is FirFunctionCall) collectInlinedLambdas(element, inlinedLambdas)
            element.acceptChildren(this)
          }
        },
    )
    var found = false
    function.accept(
        object : FirVisitorVoid() {
          override fun visitElement(element: FirElement) {
            if (found) return
            if (
                (element is FirAnonymousFunction && element !in inlinedLambdas) ||
                    element is FirAnonymousObject ||
                    element is FirCallableReferenceAccess ||
                    (element is FirRegularClass && element.symbol.classId.isLocal) ||
                    (element is FirDeclaration && element.emptiedBodyHadLocalClasses == true)
            ) {
              found = true
              return
            }
            element.acceptChildren(this)
          }
        },
    )
    return found
  }

  private fun collectInlinedLambdas(call: FirFunctionCall, into: MutableSet<FirAnonymousFunction>) {
    val callee =
        (call.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
            as? FirNamedFunctionSymbol ?: return
    if (!callee.fir.isInline) return
    for ((argument, parameter) in call.resolvedArgumentMapping ?: return) {
      if (parameter.isNoinline) continue
      val expression = (argument as? FirWrappedArgumentExpression)?.expression ?: argument
      (expression as? FirAnonymousFunctionExpression)?.let { into += it.anonymousFunction }
    }
  }

  @OptIn(DirectDeclarationsAccess::class)
  private fun containerDeclarations(function: FirFunction): List<FirDeclaration> {
    val classId = function.symbol.callableId?.classId
    if (classId != null) return regularClass(classId)?.declarations.orEmpty()
    return session.firProvider.getFirCallableContainerFile(function.symbol)?.declarations.orEmpty()
  }

  /** Why [isKeepable] last failed for [function], if it recorded one. */
  fun keepRejectReason(function: FirFunction): String? = rejectDetails[function]

  /**
   * Shapes that the ABI cannot carry as one plain method body: members of value classes or
   * interfaces, and, in the body (see [Visitor]), anonymous objects and local declarations, local
   * classes numbered after an earlier same-named declaration, `when` over an enum and `entries` of
   * a library enum (classes generated next to the caller), callable references to declarations the
   * ABI does not carry, non-Kotlin annotations (compiler plugins rewrite annotated code), reads of
   * own `const val`s whose value did not survive (unresolvable initializers) and own inline
   * properties, calls to own inline functions that are not kept, and own declarations the ABI does
   * not carry with the same signature (private ones, and protected ones reached through a synthetic
   * accessor). Default argument values are checked by the same visitor as ordinary body expressions
   * instead (safe ones are kept with real values; the backend emits the `$default` bridge from
   * them).
   */
  private fun hasLowerableShape(function: FirFunction): Boolean =
      shapeRejectReason(function) == null

  private fun shapeRejectReason(function: FirFunction): String? {
    // Default values are checked by the body visitor below with the same rules as the body
    // itself, so unsafe ones still fail loud; the backend emits the `$default` bridge from them.
    val owner = function.symbol.callableId?.classId?.let(::regularClass)
    if (
        owner != null &&
            (owner.isInlineOrValue ||
                owner.classKind == ClassKind.INTERFACE ||
                owner.classKind == ClassKind.ANNOTATION_CLASS)
    ) {
      return "not lowerable: member of ${owner.classKind.name.lowercase()} class"
    }
    return null
  }

  private val FirElement.coneTypeOrNull: ConeKotlinType?
    get() = (this as? FirResolvedTypeRef)?.coneType

  private val ConeKotlinType.classId: ClassId?
    get() =
        when (this) {
          is ConeClassLikeType -> lookupTag.classId
          is ConeFlexibleType -> lowerBound.classId
          is ConeDefinitelyNotNullType -> original.classId
          else -> null
        }

  private fun regularClass(classId: ClassId): FirRegularClass? =
      session.symbolProvider.getClassLikeSymbolByClassId(classId)?.fir as? FirRegularClass

  private fun isOwn(symbol: FirBasedSymbol<*>, origin: FirDeclarationOrigin): Boolean {
    if (isSameModuleJava(origin)) return true
    // An unchanged file of an incremental round's own target; never a stub.
    if (!origin.fromSource && isOwnDeclaration(symbol, session)) return true
    if (origin is FirDeclarationOrigin.Enhancement) {
      return ownerOrigin(symbol)?.let(::isSameModuleJava) == true
    }
    if (!origin.fromSource || origin is FirDeclarationOrigin.Java) return false
    if (origin is FirDeclarationOrigin.Plugin) return false
    val file =
        when (symbol) {
          is FirClassLikeSymbol<*> -> session.firProvider.getFirClassifierContainerFileIfAny(symbol)
          is FirCallableSymbol<*> -> session.firProvider.getFirCallableContainerFile(symbol)
          else -> null
        }
    return file != null && !isStubFile(file)
  }

  // Use-site enhanced copies of Java members carry no module info; attribute to the owner class.
  private fun ownerOrigin(symbol: FirBasedSymbol<*>): FirDeclarationOrigin? {
    val ownerClassId = (symbol as? FirCallableSymbol<*>)?.callableId?.classId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(ownerClassId)?.fir?.origin
  }

  private fun isAllowedClass(classId: ClassId): Boolean {
    // Anonymous objects and local classes are part of the body; with lowerableOnly they are
    // rejected.
    if (classId.isLocal) return true
    val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
    if (symbol.origin.fromSource || isOwnDeclaration(symbol, session)) {
      // With lowerableOnly: the ABI drops private classes.
      val private = (symbol.fir as? FirMemberDeclaration)?.effectiveVisibility?.privateApi != false
      return isOwn(symbol, symbol.origin) && !(lowerableOnly && private)
    }
    // A typealias has no class file: the reduced classpath provides it through a file facade, like
    // a top-level member.
    return isLibraryDeclarationAllowed(
        classId.asSingleFqName().asString(),
        symbol is FirTypeAliasSymbol,
    )
  }

  /**
   * Whether source-only ABI generation resolves the typealiases written in [typeRef]. The body sees
   * the expanded types, but an alias that does not resolve there is replaced by a class stub
   * fabricated for its name. Inferred types also carry the aliases of library signatures, which
   * this module never wrote and which never get a stub, so only written types are checked.
   */
  private fun isAllowedWrittenAliases(typeRef: FirResolvedTypeRef): Boolean =
      typeRef.delegatedTypeRef == null || isAllowedAliases(typeRef.coneType)

  private fun isAllowedAliases(type: ConeKotlinType): Boolean =
      when (type) {
        is ConeClassLikeType -> {
          // The arguments of an expansion are not written; those of the alias are.
          val alias = type.attributes[AbbreviatedTypeAttribute::class]?.coneType
          (alias == null || isAllowedAlias(alias)) &&
              (alias ?: type).typeArguments.all {
                (it as? ConeKotlinTypeProjection)?.type?.let(::isAllowedAliases) != false
              }
        }
        else -> true
      }

  /** A constructor called through a typealias writes the alias at the call site. */
  private fun isAllowedConstructorAlias(call: FirFunctionCall): Boolean {
    val callee = (call.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
    if (callee !is FirConstructorSymbol) return true
    return call.resolvedType.attributes[AbbreviatedTypeAttribute::class]
        ?.coneType
        ?.let(::isAllowedAlias) != false
  }

  private fun isAllowedAlias(abbreviation: ConeKotlinType): Boolean {
    val classId = (abbreviation as ConeClassLikeType).lookupTag.classId
    // The written alias resolved with the type, so the lookup finds it.
    val symbol =
        checkNotNull(session.symbolProvider.getClassLikeSymbolByClassId(classId)) { "$classId" }
    if (symbol.origin.fromSource || isOwnDeclaration(symbol, session)) {
      return isOwn(symbol, symbol.origin)
    }
    return isLibraryDeclarationAllowed(
        classId.asSingleFqName().asString(),
        symbol is FirTypeAliasSymbol,
    )
  }

  private fun isAllowedType(
      type: ConeKotlinType,
      visited: MutableSet<ConeCapturedType> = Collections.newSetFromMap(IdentityHashMap()),
  ): Boolean =
      when (type) {
        is ConeClassLikeType ->
            isAllowedClass(type.lookupTag.classId) &&
                type.typeArguments.all {
                  it is ConeStarProjection ||
                      (it is ConeKotlinTypeProjection && isAllowedType(it.type, visited))
                }
        is ConeTypeParameterType,
        is ConeIntegerLiteralType -> true
        is ConeFlexibleType ->
            isAllowedType(type.lowerBound, visited) && isAllowedType(type.upperBound, visited)
        is ConeDefinitelyNotNullType -> isAllowedType(type.original, visited)
        is ConeIntersectionType -> type.intersectedTypes.all { isAllowedType(it, visited) }
        is ConeCapturedType ->
            !visited.add(type) ||
                ((type.constructor.projection as? ConeKotlinTypeProjection)?.let {
                  isAllowedType(it.type, visited)
                } != false &&
                    type.constructor.supertypes.orEmpty().all { isAllowedType(it, visited) })
        // Error types and anything not modelled here.
        else -> false
      }

  private inner class Visitor(private val function: FirFunction) : FirVisitorVoid() {
    var keepable = true
    // First failing element, for diagnostics.
    var rejectDetail: String? = null

    override fun visitElement(element: FirElement) {
      if (!keepable) return
      keepable = isAllowed(element) && (!lowerableOnly || isLowerable(element))
      if (!keepable && rejectDetail == null) rejectDetail = describeReject(element)
      element.acceptChildren(this)
    }

    private fun isAllowed(element: FirElement): Boolean =
        element !is FirErrorExpression &&
            element !is FirErrorTypeRef &&
            (element !is FirResolvedTypeRef ||
                (isAllowedType(element.coneType) && isAllowedWrittenAliases(element))) &&
            (element !is FirFunctionCall || isAllowedConstructorAlias(element)) &&
            (element !is FirExpression ||
                runCatching { element.resolvedType }.getOrNull()?.let(::isAllowedType) == true) &&
            (element !is FirQualifiedAccessExpression ||
                listOfNotNull(element.dispatchReceiver, element.extensionReceiver).all {
                  isAllowedType(it.resolvedType)
                }) &&
            (element !is FirResolvedQualifier ||
                element.qualifierSymbol?.classId?.let(::isAllowedClass) != false) &&
            (element !is FirResolvable || isAllowedReference(element))

    private fun isAllowedReference(resolvable: FirResolvable): Boolean {
      val symbol =
          when (val reference = resolvable.calleeReference) {
            is FirErrorNamedReference -> return false
            is FirResolvedNamedReference -> reference.resolvedSymbol
            else -> return true
          }
      when (symbol) {
        is FirValueParameterSymbol,
        is FirAnonymousFunctionSymbol,
        is FirTypeParameterSymbol -> return true
        is FirClassLikeSymbol<*> -> return isAllowedClass(symbol.classId)
        is FirPropertySymbol -> if (symbol.isLocal) return true
        is FirVariableSymbol<*> -> if (symbol.callableId?.classId == null) return true
        else -> {}
      }
      val callable = (symbol as? FirCallableSymbol<*>)?.unwrapFakeOverrides() ?: return false
      val callableId = callable.callableId ?: return false
      // `when`, `try`, `!!` and `?:` resolve through synthetic calls that generate no call.
      if (callableId.packageName == SYNTHETIC_PACKAGE) return true
      if (callable.origin.fromSource || isOwnDeclaration(callable, session)) {
        return isOwn(callable, callable.origin)
      }
      val classId =
          callableId.classId
              ?: return isLibraryDeclarationAllowed(callableId.asSingleFqName().asString(), true)
      return isAllowedClass(classId)
    }

    private fun isLowerable(element: FirElement): Boolean =
        when (element) {
          is FirAnonymousObject,
          is FirRegularClass,
          is FirTypeAlias -> false
          // Lambdas generate a synthetic class in the ABI jar, which is self-contained: the
          // visitor above already checks the lambda's contents with the same rules as the body,
          // and the backend emits the class exactly as the full compile does.
          is FirAnonymousFunction -> true
          // Callable references are lowered like lambdas: the reference class (or adapter) is
          // named after the enclosing function and emitted from the same IR as in the full
          // compile. The referenced declaration must survive in the ABI with the same signature,
          // and must not be composable: the Compose plugin rewrites composable signatures, which
          // the reference class lowered in the ABI action would not match.
          is FirCallableReferenceAccess ->
              (element.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol.let {
                it is FirCallableSymbol<*> &&
                    isLowerableCallee(it.unwrapFakeOverrides()) &&
                    !hasComposeSignature(it.unwrapFakeOverrides())
              }
          is FirFunction -> element === function
          is FirAnnotation ->
              annotationFqn(element)?.let { isKeepableAnnotation(it, pluginTriggerAnnotations) } ==
                  true || isOptInMarker(element)
          is FirWhenExpression -> !isWhenOverEnum(element)
          is FirResolvable ->
              (element.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol.let {
                it !is FirCallableSymbol<*> || isLowerableCallee(it.unwrapFakeOverrides())
              }
          else -> true
        }

    private fun hasComposeSignature(callable: FirCallableSymbol<*>): Boolean {
      val declaration = callable.fir
      if (declaration.annotations.any { isComposeAnnotation(it) }) return true
      val types = buildList {
        add(declaration.returnTypeRef.coneType)
        declaration.receiverParameter?.typeRef?.coneType?.let(::add)
        (declaration as? FirFunction)?.valueParameters?.forEach { add(it.returnTypeRef.coneType) }
      }
      return types.any { hasComposeAnnotation(it) }
    }

    private fun hasComposeAnnotation(type: ConeKotlinType): Boolean =
        when (type) {
          is ConeFlexibleType -> hasComposeAnnotation(type.lowerBound)
          else ->
              type.customAnnotations.any { isComposeAnnotation(it) } ||
                  (type as? ConeClassLikeType)?.typeArguments.orEmpty().any {
                    (it as? ConeKotlinTypeProjection)?.type?.let(::hasComposeAnnotation) == true
                  }
        }

    private fun isComposeAnnotation(annotation: FirAnnotation): Boolean =
        annotationFqn(annotation)?.startsWith("androidx.compose.") == true

    // An opt-in marker (an annotation class annotated `@RequiresOptIn`) only gates use sites in the
    // frontend's opt-in checker; codegen ignores it. A stub of a dependency's marker carries no
    // `@RequiresOptIn`, so it stays rejected.
    private fun isOptInMarker(element: FirAnnotation): Boolean {
      val classId = element.annotationTypeRef.coneTypeOrNull?.classId ?: return false
      if (classId.asSingleFqName().asString() in pluginTriggerAnnotations) return false
      val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
      return REQUIRES_OPT_IN in symbol.resolvedAnnotationClassIds
    }

    private fun annotationFqn(element: FirAnnotation): String? =
        element.annotationTypeRef.coneTypeOrNull?.classId?.asSingleFqName()?.asString()

    private fun describeReject(element: FirElement): String {
      if (element is FirAnnotation) {
        return "not lowerable: annotation '${annotationFqn(element) ?: "unknown"}'"
      }
      if (
          element is FirErrorExpression ||
              element is FirErrorTypeRef ||
              (element is FirResolvable && element.calleeReference is FirErrorNamedReference)
      ) {
        return "unresolved reference"
      }
      return when (element) {
        is FirCallableReferenceAccess -> "not lowerable: callable reference"
        is FirAnonymousObject -> "not lowerable: anonymous object"
        is FirRegularClass -> "not lowerable: local class"
        is FirWhenExpression ->
            if (isWhenOverEnum(element)) "not lowerable: when over enum"
            else "not keepable: FirWhenExpression"
        else -> "not keepable: ${element::class.simpleName}"
      }
    }

    // Codegen reads a `$WhenMappings` class that it generates next to the caller.
    private fun isWhenOverEnum(expression: FirWhenExpression): Boolean {
      val subject = expression.subjectVariable?.returnTypeRef?.coneTypeOrNull ?: return false
      val kind = regularClass(subject.classId ?: return false)?.classKind
      return kind == null || kind == ClassKind.ENUM_CLASS
    }

    private fun isLowerableCallee(callable: FirCallableSymbol<*>): Boolean {
      val callableId = callable.callableId ?: return true
      if (
          callable is FirValueParameterSymbol || (callable as? FirPropertySymbol)?.isLocal == true
      ) {
        return true
      }
      if (!callable.origin.fromSource) {
        // `entries` of a library enum reads an `$EntriesMappings` class generated next to the
        // caller.
        return callableId.asSingleFqName() != ENUM_ENTRIES_FUNCTION &&
            !(callable is FirPropertySymbol && callableId.callableName.asString() == "entries")
      }
      // The ABI drops private declarations, and callers reach a protected member through a
      // synthetic accessor unless the inline function is itself protected.
      val status = (callable.fir as? FirMemberDeclaration)?.status as? FirResolvedDeclarationStatus
      if (status == null || status.effectiveVisibility.privateApi) return false
      if (
          status.visibility == Visibilities.Protected &&
              (function.status as? FirResolvedDeclarationStatus)?.visibility !=
                  Visibilities.Protected
      ) {
        return false
      }
      return when (callable) {
        // An own inline property, like an own inline function, is inlined from its kept accessor.
        is FirPropertySymbol ->
            (!callable.fir.isConst || isHealthyConst(callable.fir)) &&
                callable.fir.getter.let { it == null || !it.isInline || isKeepable(it) } &&
                callable.fir.setter.let { it == null || !it.isInline || isKeepable(it) }
        is FirNamedFunctionSymbol -> !callable.fir.isInline || isKeepable(callable.fir)
        else -> true
      }
    }

    // A same-module `const val` read is keepable only when the constant survives into the ABI
    // with its value; see [isFoldableConst].
    private fun isHealthyConst(property: FirProperty): Boolean =
        isFoldableConst(property) && foldableConstLiteral(property) != null
  }

  // Public for tests: the keepability predicate without a compilation.
  companion object {
    val SYNTHETIC_PACKAGE = FqName("_synthetic")
    private val REQUIRES_OPT_IN = ClassId.topLevel(FqName("kotlin.RequiresOptIn"))
    val ENUM_ENTRIES_FUNCTION = FqName("kotlin.enums.enumEntries")

    // Package-level allowlist: every annotation in these packages is lint-only — no
    // code-rewriting compiler plugin reads them, so a kept body means the same in the
    // source-only ABI and the full compile. Evidence per package:
    // - androidx.annotation / javax.annotation / org.jetbrains.annotations /
    //   com.facebook.infer.annotation: zero source references in every Meta kotlinc
    //   plugin (di, ditransformer, dataclassgenerate, litho-compiler-plugin, whole
    //   fbandroid/java/com/facebook/kotlin/compilerplugins tree) and in the prelude
    //   kotlin toolchain; the JetBrains triggers that matter here are option-driven
    //   (allopen/noarg/sam-with-receiver, covered by the pluginTriggerAnnotations
    //   subtraction) or hardcoded in banned packages (parcelize/serialization/compose).
    // Deliberately absent (fail-closed default rejects them): androidx.compose.*,
    // com.facebook.ultralight.*, com.facebook.litho.annotations.*, kotlinx.serialization.*,
    // kotlinx.parcelize.*. Mixed packages (e.g. com.whatsapp.annotation, which hosts the
    // allopen trigger OpenFinalClass) are never listed here — use KEEPABLE_ANNOTATIONS.
    val KEEPABLE_ANNOTATION_PACKAGES = setOf(
        // Pre-existing allowance: compiler-blessed annotations (OptIn, Suppress, ...).
        "kotlin",
        "androidx.annotation",
        "javax.annotation",
        "org.jetbrains.annotations",
        "com.facebook.infer.annotation",
    )

    // FQN-level allowlist for harmless annotations inside otherwise-mixed packages.
    // Add entries only with per-annotation no-reader evidence.
    val KEEPABLE_ANNOTATIONS = setOf<String>(
        // Lint-only (`@Retention(CLASS)` in android.jar); no compiler plugin reads it.
        "android.annotation.SuppressLint",
    )

    // Whether an annotation may ride along on a kept inline body: allowlisted by
    // package or FQN, and not named as a trigger in this compilation's plugin options
    // (an allopen/noarg/sam-with-receiver `annotation=` value or preset expansion).
    // Unknown packages stay rejected (fail-closed).
    fun isKeepableAnnotation(annotationFqn: String, pluginTriggers: Set<String>): Boolean {
      if (annotationFqn in pluginTriggers) return false
      if (annotationFqn in KEEPABLE_ANNOTATIONS) return true
      return KEEPABLE_ANNOTATION_PACKAGES.any {
        annotationFqn == it || annotationFqn.startsWith("$it.")
      }
    }
  }
}

// A same-module `const val` read is keepable only when the constant survives into the ABI
// with its value. The FIR sanitizer clears the initializer of a const whose value cannot be
// resolved (a kept body reading such a const would inline a zeroed value at callers), so
// mirror the sanitizer's own health predicate here: an error expression anywhere in the
// initializer (following const references, which fold from the referenced value) makes the
// const unfoldable. A healthy const reaches the ABI jar with its ConstantValue, exactly
// like a dependency const, which the library branch already allows. The abi-gen restore
// pass also uses this to pre-fold such reads to literals: stock ConstEvaluationLowering
// misfires on a const-read feeding an interpreted call whose callee abi-gen stubbed to an
// empty body ("Cannot convert IrExpression to ConstantValue"), while the literal form
// compiles cleanly.
fun isFoldableConst(property: FirProperty): Boolean {
  if (!property.origin.fromSource || !property.isConst) return false
  val initializer = property.initializer ?: return false
  return !constInitializerHasErrors(initializer, HashSet())
}

/** Walks a healthy const to its ultimate literal, following const-to-const chains. */
fun foldableConstLiteral(property: FirProperty): FirLiteralExpression? {
  val seen = HashSet<FirPropertySymbol>()
  var current: FirElement? = property.initializer
  while (current != null) {
    when (current) {
      is FirLiteralExpression -> return current
      is FirQualifiedAccessExpression -> {
        val symbol =
            (current.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
                as? FirPropertySymbol ?: return null
        if (!symbol.fir.isConst || !seen.add(symbol)) return null
        current = symbol.fir.initializer
      }
      else -> return null
    }
  }
  return null
}

private fun constInitializerHasErrors(
    element: FirElement,
    seenConsts: MutableSet<FirPropertySymbol>,
): Boolean =
    when (element) {
      is FirErrorExpression -> true
      is FirNamedArgumentExpression -> constInitializerHasErrors(element.expression, seenConsts)
      is FirWrappedArgumentExpression -> constInitializerHasErrors(element.expression, seenConsts)
      is FirVarargArgumentsExpression ->
          element.arguments.any { constInitializerHasErrors(it, seenConsts) }
      is FirGetClassCall ->
          runCatching {
                val argument = element.argument
                if (argument is FirQualifiedAccessExpression) {
                  argument.resolvedType is ConeErrorType
                } else {
                  element.resolvedType is ConeErrorType
                }
              }
              .getOrDefault(true)
      is FirQualifiedAccessExpression ->
          runCatching {
                if (
                    element.resolvedType is ConeErrorType ||
                        element.calleeReference is FirErrorNamedReference
                ) {
                  return@runCatching true
                }
                val calleeRef = element.calleeReference
                if (calleeRef is FirResolvedNamedReference) {
                  val symbol = calleeRef.resolvedSymbol
                  if (symbol is FirPropertySymbol && symbol.fir.isConst) {
                    if (!seenConsts.add(symbol)) return@runCatching true
                    val refInitializer = symbol.fir.initializer
                    if (
                        refInitializer == null ||
                            constInitializerHasErrors(refInitializer, seenConsts) ||
                            isTodoCall(refInitializer)
                    ) {
                      return@runCatching true
                    }
                    return@runCatching false
                  }
                }
                false
              }
              .getOrDefault(true)
      is FirFunctionCall ->
          runCatching {
                element.resolvedType is ConeErrorType ||
                    element.calleeReference is FirErrorNamedReference
              }
              .getOrDefault(true)
      else -> false
    }

private fun isTodoCall(element: FirElement): Boolean {
  if (element !is FirFunctionCall) return false
  val calleeRef = element.calleeReference
  if (calleeRef is FirResolvedNamedReference) {
    return calleeRef.name.asString() == "TODO"
  }
  return false
}
