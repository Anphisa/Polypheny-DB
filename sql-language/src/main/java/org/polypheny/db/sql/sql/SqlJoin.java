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
 */

package org.polypheny.db.sql.sql;


import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Objects;
import org.polypheny.db.algebra.constant.JoinConditionType;
import org.polypheny.db.algebra.constant.JoinType;
import org.polypheny.db.algebra.constant.Kind;
import org.polypheny.db.languages.ParserPos;
import org.polypheny.db.nodes.Literal;
import org.polypheny.db.nodes.Node;
import org.polypheny.db.nodes.Operator;
import org.polypheny.db.type.PolyType;
import org.polypheny.db.util.ImmutableNullableList;
import org.polypheny.db.util.Util;


/**
 * Parse tree node representing a {@code JOIN} clause.
 */
public class SqlJoin extends SqlCall {

    public static final SqlJoinOperator OPERATOR = new SqlJoinOperator();

    SqlNode left;

    /**
     * Operand says whether this is a natural join. Must be constant TRUE or FALSE.
     */
    SqlLiteral natural;

    /**
     * Value must be a {@link SqlLiteral}, one of the integer codes for {@link JoinType}.
     */
    SqlLiteral joinType;
    SqlNode right;

    /**
     * Value must be a {@link SqlLiteral}, one of the integer codes for {@link JoinConditionType}.
     */
    SqlLiteral conditionType;
    SqlNode condition;


    public SqlJoin( ParserPos pos, SqlNode left, SqlLiteral natural, SqlLiteral joinType, SqlNode right, SqlLiteral conditionType, SqlNode condition ) {
        super( pos );
        this.left = left;
        this.natural = Objects.requireNonNull( natural );
        this.joinType = Objects.requireNonNull( joinType );
        this.right = right;
        this.conditionType = Objects.requireNonNull( conditionType );
        this.condition = condition;

        Preconditions.checkArgument( natural.getTypeName() == PolyType.BOOLEAN );
        Objects.requireNonNull( conditionType.symbolValue( JoinConditionType.class ) );
        Objects.requireNonNull( joinType.symbolValue( JoinType.class ) );
    }


    @Override
    public Operator getOperator() {
        return OPERATOR;
    }


    @Override
    public Kind getKind() {
        return Kind.JOIN;
    }


    @Override
    public List<Node> getOperandList() {
        return ImmutableNullableList.of( left, natural, joinType, right, conditionType, condition );
    }


    @Override
    public List<SqlNode> getSqlOperandList() {
        return ImmutableNullableList.of( left, natural, joinType, right, conditionType, condition );
    }


    @Override
    public void setOperand( int i, Node operand ) {
        switch ( i ) {
            case 0:
                left = (SqlNode) operand;
                break;
            case 1:
                natural = (SqlLiteral) operand;
                break;
            case 2:
                joinType = (SqlLiteral) operand;
                break;
            case 3:
                right = (SqlNode) operand;
                break;
            case 4:
                conditionType = (SqlLiteral) operand;
                break;
            case 5:
                condition = (SqlNode) operand;
                break;
            default:
                throw new AssertionError( i );
        }
    }


    public final SqlNode getCondition() {
        return condition;
    }


    /**
     * Returns a {@link JoinConditionType}, never null.
     */
    public final JoinConditionType getConditionType() {
        return conditionType.symbolValue( JoinConditionType.class );
    }


    public SqlLiteral getConditionTypeNode() {
        return conditionType;
    }


    /**
     * Returns a {@link JoinType}, never null.
     */
    public final JoinType getJoinType() {
        return joinType.symbolValue( JoinType.class );
    }


    public SqlLiteral getJoinTypeNode() {
        return joinType;
    }


    public final SqlNode getLeft() {
        return left;
    }


    public void setLeft( SqlNode left ) {
        this.left = left;
    }


    public final boolean isNatural() {
        return natural.booleanValue();
    }


    public final SqlLiteral isNaturalNode() {
        return natural;
    }


    public final SqlNode getRight() {
        return right;
    }


    public void setRight( SqlNode right ) {
        this.right = right;
    }


    /**
     * <code>SqlJoinOperator</code> describes the syntax of the SQL <code>JOIN</code> operator. Since there is only one such operator, this class is almost certainly a singleton.
     */
    public static class SqlJoinOperator extends SqlOperator {

        private static final SqlWriter.FrameType FRAME_TYPE = SqlWriter.FrameTypeEnum.create( "USING" );


        private SqlJoinOperator() {
            super( "JOIN", Kind.JOIN, 16, true, null, null, null );
        }


        @Override
        public SqlSyntax getSqlSyntax() {
            return SqlSyntax.SPECIAL;
        }


        @Override
        public SqlCall createCall( Literal functionQualifier, ParserPos pos, Node... operands ) {
            assert functionQualifier == null;
            return new SqlJoin(
                    pos,
                    (SqlNode) operands[0],
                    (SqlLiteral) operands[1],
                    (SqlLiteral) operands[2],
                    (SqlNode) operands[3],
                    (SqlLiteral) operands[4],
                    (SqlNode) operands[5] );
        }


        @Override
        public void unparse( SqlWriter writer, SqlCall call, int leftPrec, int rightPrec ) {
            final SqlJoin join = (SqlJoin) call;

            final SqlWriter.Frame joinFrame = writer.startList( SqlWriter.FrameTypeEnum.JOIN );
            join.left.unparse( writer, leftPrec, getLeftPrec() );
            String natural = "";
            if ( join.isNatural() ) {
                natural = "NATURAL ";
            }
            switch ( join.getJoinType() ) {
                case COMMA:
                    writer.sep( ",", true );
                    break;
                case CROSS:
                    writer.sep( natural + "CROSS JOIN" );
                    break;
                case FULL:
                    writer.sep( natural + "FULL JOIN" );
                    break;
                case INNER:
                    writer.sep( natural + "INNER JOIN" );
                    break;
                case LEFT:
                    writer.sep( natural + "LEFT JOIN" );
                    break;
                case LEFT_SEMI_JOIN:
                    writer.sep( natural + "LEFT SEMI JOIN" );
                    break;
                case RIGHT:
                    writer.sep( natural + "RIGHT JOIN" );
                    break;
                default:
                    throw Util.unexpected( join.getJoinType() );
            }
            join.right.unparse( writer, getRightPrec(), rightPrec );
            if ( join.condition != null ) {
                switch ( join.getConditionType() ) {
                    case USING:
                        // No need for an extra pair of parens -- the condition is a list. The result is something like "USING (deptno, gender)".
                        writer.keyword( "USING" );
                        assert join.condition instanceof SqlNodeList;
                        final SqlWriter.Frame frame = writer.startList( FRAME_TYPE, "(", ")" );
                        join.condition.unparse( writer, 0, 0 );
                        writer.endList( frame );
                        break;

                    case ON:
                        writer.keyword( "ON" );
                        join.condition.unparse( writer, leftPrec, rightPrec );
                        break;

                    default:
                        throw Util.unexpected( join.getConditionType() );
                }
            }
            writer.endList( joinFrame );
        }

    }

}
