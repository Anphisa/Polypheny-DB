/*
 * Copyright 2019-2021 The Polypheny Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file incorporates code covered by the following terms:
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.polypheny.db.adapter.enumerable;


import com.google.common.collect.ImmutableList;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.BlockStatement;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.MemberDeclaration;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Types;
import org.polypheny.db.adapter.DataContext;
import org.polypheny.db.adapter.java.JavaTypeFactory;
import org.polypheny.db.algebra.AlgCollationTraitDef;
import org.polypheny.db.algebra.AlgDistributionTraitDef;
import org.polypheny.db.algebra.AlgNode;
import org.polypheny.db.algebra.constant.ConformanceEnum;
import org.polypheny.db.algebra.core.Calc;
import org.polypheny.db.algebra.metadata.AlgMdCollation;
import org.polypheny.db.algebra.metadata.AlgMdDistribution;
import org.polypheny.db.algebra.metadata.AlgMetadataQuery;
import org.polypheny.db.plan.AlgOptCluster;
import org.polypheny.db.plan.AlgOptPredicateList;
import org.polypheny.db.plan.AlgTraitSet;
import org.polypheny.db.rex.RexBuilder;
import org.polypheny.db.rex.RexProgram;
import org.polypheny.db.rex.RexSimplify;
import org.polypheny.db.rex.RexUtil;
import org.polypheny.db.util.BuiltInMethod;
import org.polypheny.db.util.Conformance;
import org.polypheny.db.util.Pair;


/**
 * Implementation of {@link Calc} in {@link EnumerableConvention enumerable calling convention}.
 */
public class EnumerableCalc extends Calc implements EnumerableAlg {

    /**
     * Creates an EnumerableCalc.
     *
     * Use {@link #create} unless you know what you're doing.
     */
    public EnumerableCalc( AlgOptCluster cluster, AlgTraitSet traitSet, AlgNode input, RexProgram program ) {
        super( cluster, traitSet, input, program );
        assert getConvention() instanceof EnumerableConvention;
        assert !program.containsAggs();
    }


    /**
     * Creates an EnumerableCalc.
     */
    public static EnumerableCalc create( final AlgNode input, final RexProgram program ) {
        final AlgOptCluster cluster = input.getCluster();
        final AlgMetadataQuery mq = cluster.getMetadataQuery();
        final AlgTraitSet traitSet = cluster.traitSet()
                .replace( EnumerableConvention.INSTANCE )
                .replaceIfs( AlgCollationTraitDef.INSTANCE, () -> AlgMdCollation.calc( mq, input, program ) )
                .replaceIf( AlgDistributionTraitDef.INSTANCE, () -> AlgMdDistribution.calc( mq, input, program ) );
        return new EnumerableCalc( cluster, traitSet, input, program );
    }


    @Override
    public EnumerableCalc copy( AlgTraitSet traitSet, AlgNode child, RexProgram program ) {
        // we do not need to copy program; it is immutable
        return new EnumerableCalc( getCluster(), traitSet, child, program );
    }


    @Override
    public Result implement( EnumerableAlgImplementor implementor, Prefer pref ) {
        final JavaTypeFactory typeFactory = implementor.getTypeFactory();
        final BlockBuilder builder = new BlockBuilder();
        final EnumerableAlg child = (EnumerableAlg) getInput();

        final Result result = implementor.visitChild( this, 0, child, pref );

        final PhysType physType = PhysTypeImpl.of( typeFactory, getRowType(), pref.prefer( result.format ) );

        // final Enumerable<Employee> inputEnumerable = <<child adapter>>;
        // return new Enumerable<IntString>() {
        //     Enumerator<IntString> enumerator() {
        //         return new Enumerator<IntString>() {
        //             public void reset() {
        // ...
        Type outputJavaType = physType.getJavaRowType();
        final Type enumeratorType = Types.of( Enumerator.class, outputJavaType );
        Type inputJavaType = result.physType.getJavaRowType();
        ParameterExpression inputEnumerator = Expressions.parameter( Types.of( Enumerator.class, inputJavaType ), "inputEnumerator" );
        Expression input = RexToLixTranslator.convert( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_CURRENT.method ), inputJavaType );

        final RexBuilder rexBuilder = getCluster().getRexBuilder();
        final AlgMetadataQuery mq = AlgMetadataQuery.instance();
        final AlgOptPredicateList predicates = mq.getPulledUpPredicates( child );
        final RexSimplify simplify = new RexSimplify( rexBuilder, predicates, RexUtil.EXECUTOR );
        final RexProgram program = this.program.normalize( rexBuilder, simplify );

        BlockStatement moveNextBody;
        if ( program.getCondition() == null ) {
            moveNextBody = Blocks.toFunctionBlock( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_MOVE_NEXT.method ) );
        } else {
            final BlockBuilder builder2 = new BlockBuilder();
            Expression condition =
                    RexToLixTranslator.translateCondition(
                            program,
                            typeFactory,
                            builder2,
                            new RexToLixTranslator.InputGetterImpl( Collections.singletonList( Pair.of( input, result.physType ) ) ),
                            implementor.allCorrelateVariables, implementor.getConformance() );
            builder2.add(
                    Expressions.ifThen(
                            condition,
                            Expressions.return_( null, Expressions.constant( true ) ) ) );
            moveNextBody =
                    Expressions.block(
                            Expressions.while_( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_MOVE_NEXT.method ), builder2.toBlock() ),
                            Expressions.return_( null, Expressions.constant( false ) ) );
        }

        final BlockBuilder builder3 = new BlockBuilder();
        final Conformance conformance = (Conformance) implementor.map.getOrDefault( "_conformance", ConformanceEnum.DEFAULT );
        UnwindContext unwindContext = new UnwindContext();
        List<Expression> expressions =
                RexToLixTranslator.translateProjects(
                        program,
                        typeFactory,
                        conformance,
                        builder3,
                        physType,
                        DataContext.ROOT,
                        new RexToLixTranslator.InputGetterImpl( Collections.singletonList( Pair.of( input, result.physType ) ) ),
                        implementor.allCorrelateVariables,
                        unwindContext );
        builder3.add( Expressions.return_( null, physType.record( expressions ) ) );
        BlockStatement currentBody = builder3.toBlock();

        final Expression inputEnumerable = builder.append( builder.newName( "inputEnumerable" + System.nanoTime() ), result.block, false );
        final Expression body;
        if ( !unwindContext.useUnwind ) {
            body = Expressions.new_(
                    enumeratorType,
                    EnumUtils.NO_EXPRS,
                    Expressions.list(
                            Expressions.fieldDecl(
                                    Modifier.PUBLIC | Modifier.FINAL,
                                    inputEnumerator,
                                    Expressions.call( inputEnumerable, BuiltInMethod.ENUMERABLE_ENUMERATOR.method ) ),
                            EnumUtils.overridingMethodDecl(
                                    BuiltInMethod.ENUMERATOR_RESET.method,
                                    EnumUtils.NO_PARAMS,
                                    Blocks.toFunctionBlock( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_RESET.method ) ) ),
                            EnumUtils.overridingMethodDecl(
                                    BuiltInMethod.ENUMERATOR_MOVE_NEXT.method,
                                    EnumUtils.NO_PARAMS,
                                    moveNextBody ),
                            EnumUtils.overridingMethodDecl(
                                    BuiltInMethod.ENUMERATOR_CLOSE.method,
                                    EnumUtils.NO_PARAMS,
                                    Blocks.toFunctionBlock( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_CLOSE.method ) ) ),
                            Expressions.methodDecl(
                                    Modifier.PUBLIC,
                                    EnumUtils.BRIDGE_METHODS
                                            ? Object.class
                                            : outputJavaType,
                                    "current",
                                    EnumUtils.NO_PARAMS,
                                    currentBody ) ) );

        } else {
            BlockBuilder unwindBlock = new BlockBuilder();
            unwindBlock.add(
                    Expressions.ifThenElse(
                            Expressions.greaterThan( unwindContext._i, Expressions.constant( 1 ) ),
                            Expressions.block(
                                    Expressions.statement(
                                            Expressions.assign(
                                                    unwindContext._i,
                                                    Expressions.subtract(
                                                            unwindContext._i,
                                                            Expressions.constant( 1 ) ) ) ),
                                    Expressions.return_( null, Expressions.constant( true ) ) ),
                            Expressions.block(
                                    Expressions.statement( Expressions.assign( unwindContext._unset, Expressions.constant( true ) ) ),
                                    Expressions.statement( Expressions.assign( unwindContext._i, Expressions.constant( 0 ) ) ),
                                    Expressions.return_( null, Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_MOVE_NEXT.method ) ) ) )
            );
            moveNextBody = unwindBlock.toBlock();

            body = Expressions.new_(
                    enumeratorType,
                    EnumUtils.NO_EXPRS,
                    Expressions.list(
                            Expressions.fieldDecl( Modifier.PUBLIC, unwindContext._list, Expressions.constant( Collections.emptyList() ) ),
                            Expressions.fieldDecl( Modifier.PUBLIC, unwindContext._i, Expressions.constant( 0 ) ),
                            Expressions.fieldDecl( Modifier.PUBLIC, unwindContext._unset, Expressions.constant( true ) ),
                            Expressions.fieldDecl(
                                    Modifier.PUBLIC | Modifier.FINAL,
                                    inputEnumerator,
                                    Expressions.call( inputEnumerable, BuiltInMethod.ENUMERABLE_ENUMERATOR.method ) ),
                            EnumUtils.overridingMethodDecl(
                                    BuiltInMethod.ENUMERATOR_RESET.method,
                                    EnumUtils.NO_PARAMS,
                                    Blocks.toFunctionBlock( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_RESET.method ) ) ),
                            EnumUtils.overridingMethodDecl(
                                    BuiltInMethod.ENUMERATOR_MOVE_NEXT.method,
                                    EnumUtils.NO_PARAMS,
                                    moveNextBody ),
                            EnumUtils.overridingMethodDecl(
                                    BuiltInMethod.ENUMERATOR_CLOSE.method,
                                    EnumUtils.NO_PARAMS,
                                    Blocks.toFunctionBlock( Expressions.call( inputEnumerator, BuiltInMethod.ENUMERATOR_CLOSE.method ) ) ),
                            Expressions.methodDecl(
                                    Modifier.PUBLIC,
                                    EnumUtils.BRIDGE_METHODS
                                            ? Object.class
                                            : outputJavaType,
                                    "current",
                                    EnumUtils.NO_PARAMS,
                                    currentBody ) ) );
        }

        builder.add(
                Expressions.return_(
                        null,
                        Expressions.new_(
                                BuiltInMethod.ABSTRACT_ENUMERABLE_CTOR.constructor,
                                // TODO: generics
                                //   Collections.singletonList(inputRowType),
                                EnumUtils.NO_EXPRS,
                                ImmutableList.<MemberDeclaration>of( Expressions.methodDecl( Modifier.PUBLIC, enumeratorType, BuiltInMethod.ENUMERABLE_ENUMERATOR.method.getName(), EnumUtils.NO_PARAMS, Blocks.toFunctionBlock( body ) ) ) ) ) );
        return implementor.result( physType, builder.toBlock() );
    }


    @Override
    public RexProgram getProgram() {
        return program;
    }

}

