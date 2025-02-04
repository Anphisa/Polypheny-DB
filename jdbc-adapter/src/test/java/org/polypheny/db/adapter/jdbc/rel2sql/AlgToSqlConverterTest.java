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

package org.polypheny.db.adapter.jdbc.rel2sql;


import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.Function;
import org.junit.Ignore;
import org.junit.Test;
import org.polypheny.db.adapter.DataContext.SlimDataContext;
import org.polypheny.db.adapter.java.JavaTypeFactory;
import org.polypheny.db.adapter.java.ReflectiveSchema;
import org.polypheny.db.adapter.jdbc.rel2sql.AlgToSqlConverter.PlainAlgToSqlConverter;
import org.polypheny.db.algebra.AlgNode;
import org.polypheny.db.algebra.constant.NullCollation;
import org.polypheny.db.algebra.operators.OperatorName;
import org.polypheny.db.algebra.rules.UnionMergeRule;
import org.polypheny.db.algebra.type.AlgDataTypeSystemImpl;
import org.polypheny.db.catalog.Catalog.SchemaType;
import org.polypheny.db.languages.NodeToAlgConverter;
import org.polypheny.db.languages.NodeToAlgConverter.Config;
import org.polypheny.db.languages.OperatorRegistry;
import org.polypheny.db.languages.Parser;
import org.polypheny.db.languages.Parser.ParserConfig;
import org.polypheny.db.nodes.Node;
import org.polypheny.db.plan.AlgOptPlanner;
import org.polypheny.db.plan.AlgTraitDef;
import org.polypheny.db.plan.hep.HepPlanner;
import org.polypheny.db.plan.hep.HepProgram;
import org.polypheny.db.plan.hep.HepProgramBuilder;
import org.polypheny.db.prepare.ContextImpl;
import org.polypheny.db.prepare.JavaTypeFactoryImpl;
import org.polypheny.db.rex.RexNode;
import org.polypheny.db.runtime.FlatLists;
import org.polypheny.db.schema.FoodmartSchema;
import org.polypheny.db.schema.PolyphenyDbSchema;
import org.polypheny.db.schema.SchemaPlus;
import org.polypheny.db.schema.ScottSchema;
import org.polypheny.db.sql.core.SqlLanguagelDependant;
import org.polypheny.db.sql.sql.SqlCall;
import org.polypheny.db.sql.sql.SqlDialect;
import org.polypheny.db.sql.sql.SqlDialect.Context;
import org.polypheny.db.sql.sql.SqlDialect.DatabaseProduct;
import org.polypheny.db.sql.sql.SqlNode;
import org.polypheny.db.sql.sql.SqlSelect;
import org.polypheny.db.sql.sql.SqlWriter;
import org.polypheny.db.sql.sql.dialect.HiveSqlDialect;
import org.polypheny.db.sql.sql.dialect.JethroDataSqlDialect;
import org.polypheny.db.sql.sql.dialect.MysqlSqlDialect;
import org.polypheny.db.sql.sql.dialect.PolyphenyDbSqlDialect;
import org.polypheny.db.sql.sql.dialect.PostgresqlSqlDialect;
import org.polypheny.db.test.Matchers;
import org.polypheny.db.tools.AlgBuilder;
import org.polypheny.db.tools.FrameworkConfig;
import org.polypheny.db.tools.Frameworks;
import org.polypheny.db.tools.Planner;
import org.polypheny.db.tools.Program;
import org.polypheny.db.tools.Programs;
import org.polypheny.db.tools.RuleSet;
import org.polypheny.db.tools.RuleSets;
import org.polypheny.db.type.PolyType;


/**
 * Tests for {@link AlgToSqlConverter}.
 */
public class AlgToSqlConverterTest extends SqlLanguagelDependant {

    static final Config DEFAULT_REL_CONFIG =
            NodeToAlgConverter.configBuilder()
                    .trimUnusedFields( false )
                    .convertTableAccess( false )
                    .build();

    static final Config NO_EXPAND_CONFIG =
            NodeToAlgConverter.configBuilder()
                    .trimUnusedFields( false )
                    .convertTableAccess( false )
                    .expand( false )
                    .build();


    /**
     * Initiates a test case with a given SQL query.
     */
    private Sql sql( String sql ) {
        final SchemaPlus schema = Frameworks
                .createRootSchema( true )
                .add( "foodmart", new ReflectiveSchema( new FoodmartSchema() ), SchemaType.RELATIONAL );
        return new Sql( schema, sql, PolyphenyDbSqlDialect.DEFAULT, DEFAULT_REL_CONFIG, ImmutableList.of() );
    }


    private static Planner getPlanner( List<AlgTraitDef> traitDefs, ParserConfig parserConfig, SchemaPlus schema, Config sqlToRelConf, Program... programs ) {
        final SchemaPlus rootSchema = Frameworks.createRootSchema( false );
        final FrameworkConfig config = Frameworks.newConfigBuilder()
                .parserConfig( parserConfig )
                .defaultSchema( schema )
                .traitDefs( traitDefs )
                .sqlToRelConverterConfig( sqlToRelConf )
                .programs( programs )
                .prepareContext( new ContextImpl(
                        PolyphenyDbSchema.from( rootSchema ),
                        new SlimDataContext() {
                            @Override
                            public JavaTypeFactory getTypeFactory() {
                                return new JavaTypeFactoryImpl();
                            }
                        },
                        "",
                        0,
                        0,
                        null ) )
                .build();
        return Frameworks.getPlanner( config );
    }


    private static JethroDataSqlDialect jethroDataSqlDialect() {
        Context dummyContext = SqlDialect.EMPTY_CONTEXT
                .withDatabaseProduct( DatabaseProduct.JETHRO )
                .withDatabaseMajorVersion( 1 )
                .withDatabaseMinorVersion( 0 )
                .withDatabaseVersion( "1.0" )
                .withIdentifierQuoteString( "\"" )
                .withNullCollation( NullCollation.HIGH )
                .withJethroInfo( JethroDataSqlDialect.JethroInfo.EMPTY );
        return new JethroDataSqlDialect( dummyContext );
    }


    private static MysqlSqlDialect mySqlDialect( NullCollation nullCollation ) {
        return new MysqlSqlDialect( SqlDialect.EMPTY_CONTEXT
                .withDatabaseProduct( DatabaseProduct.MYSQL )
                .withIdentifierQuoteString( "`" )
                .withNullCollation( nullCollation ) );
    }


    /**
     * Creates a AlgBuilder.
     */
    private static AlgBuilder algBuilder() {
        // Creates a config based on the "scott" schema.
        final SchemaPlus schema = Frameworks.createRootSchema( true ).add( "scott", new ReflectiveSchema( new ScottSchema() ), SchemaType.RELATIONAL );
        Frameworks.ConfigBuilder configBuilder = Frameworks.newConfigBuilder()
                .parserConfig( Parser.ParserConfig.DEFAULT )
                .defaultSchema( schema )
                .traitDefs( (List<AlgTraitDef>) null )
                .programs( Programs.heuristicJoinOrder( Programs.RULE_SET, true, 2 ) )
                .prepareContext( new ContextImpl(
                        PolyphenyDbSchema.from( schema ),
                        new SlimDataContext() {
                            @Override
                            public JavaTypeFactory getTypeFactory() {
                                return new JavaTypeFactoryImpl();
                            }
                        },
                        "",
                        0,
                        0,
                        null ) );

        return AlgBuilder.create( configBuilder.build() );
    }


    /**
     * Converts a relational expression to SQL.
     */
    private String toSql( AlgNode root ) {
        return toSql( root, DatabaseProduct.POLYPHENYDB.getDialect() );
    }


    /**
     * Converts a relational expression to SQL in a given dialect.
     */
    private static String toSql( AlgNode root, SqlDialect dialect ) {
        final AlgToSqlConverter converter = new PlainAlgToSqlConverter( dialect );
        final SqlNode sqlNode = converter.visitChild( 0, root ).asStatement();
        return sqlNode.toSqlString( dialect ).getSql();
    }


    @Test
    public void testSimpleSelectStarFromProductTable() {
        String query = "select * from \"product\"";
        sql( query ).ok( "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\nFROM \"foodmart\".\"product\"" );
    }


    @Test
    public void testSimpleSelectQueryFromProductTable() {
        String query = "select \"product_id\", \"product_class_id\" from \"product\"";
        final String expected = "SELECT \"product_id\", \"product_class_id\"\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithWhereClauseOfLessThan() {
        String query = "select \"product_id\", \"shelf_width\"  from \"product\" where \"product_id\" < 10";
        final String expected = "SELECT \"product_id\", \"shelf_width\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_id\" < 10";
        sql( query ).ok( expected );
    }


    @Test
    @Ignore("Unnecessary casts")
    public void testSelectQueryWithWhereClauseOfBasicOperators() {
        String query = "select * from \"product\" where (\"product_id\" = 10 OR \"product_id\" <= 5) AND (80 >= \"shelf_width\" OR \"shelf_width\" > 30)";
        final String expected = "SELECT *\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE (\"product_id\" = 10 OR \"product_id\" <= 5) AND (80 >= \"shelf_width\" OR \"shelf_width\" > 30)";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithGroupBy() {
        String query = "select count(*) from \"product\" group by \"product_class_id\", \"product_id\"";
        final String expected = "SELECT COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\", \"product_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithGroupByEmpty() {
        final String sql0 = "select count(*) from \"product\" group by ()";
        final String sql1 = "select count(*) from \"product\"";
        final String expected = "SELECT COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"";
        final String expectedMySql = "SELECT COUNT(*)\n"
                + "FROM `foodmart`.`product`";
        sql( sql0 )
                .ok( expected )
                .withMysql()
                .ok( expectedMySql );
        sql( sql1 )
                .ok( expected )
                .withMysql()
                .ok( expectedMySql );
    }


    @Test
    public void testSelectQueryWithGroupByEmpty2() {
        final String query = "select 42 as c from \"product\" group by ()";
        final String expected = "SELECT 42 AS \"C\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY ()";
        final String expectedMySql = "SELECT 42 AS `C`\n"
                + "FROM `foodmart`.`product`\n"
                + "GROUP BY ()";
        sql( query )
                .ok( expected )
                .withMysql()
                .ok( expectedMySql );
    }


    @Test
    public void testSelectQueryWithMinAggregateFunction() {
        String query = "select min(\"net_weight\") from \"product\" group by \"product_class_id\" ";
        final String expected = "SELECT MIN(\"net_weight\")\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithMinAggregateFunction1() {
        String query = "select \"product_class_id\", min(\"net_weight\") from \"product\" group by \"product_class_id\"";
        final String expected = "SELECT \"product_class_id\", MIN(\"net_weight\")\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithSumAggregateFunction() {
        String query = "select sum(\"net_weight\") from \"product\" group by \"product_class_id\" ";
        final String expected = "SELECT SUM(\"net_weight\")\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithMultipleAggregateFunction() {
        String query = "select sum(\"net_weight\"), min(\"low_fat\"), count(*) from \"product\" group by \"product_class_id\" ";
        final String expected = "SELECT SUM(\"net_weight\"), MIN(\"low_fat\"),"
                + " COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithMultipleAggregateFunction1() {
        String query = "select \"product_class_id\", sum(\"net_weight\"), min(\"low_fat\"), count(*) from \"product\" group by \"product_class_id\" ";
        final String expected = "SELECT \"product_class_id\","
                + " SUM(\"net_weight\"), MIN(\"low_fat\"), COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithGroupByAndProjectList() {
        String query = "select \"product_class_id\", \"product_id\", count(*) from \"product\" group by \"product_class_id\", \"product_id\"  ";
        final String expected = "SELECT \"product_class_id\", \"product_id\","
                + " COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\", \"product_id\"";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "JDBC adapter may generate casts on PostgreSQL for VARCHAR type exceeding max length".
     */
    @Test
    public void testCastLongVarchar1() {
        final String query = "select cast(\"store_id\" as VARCHAR(10485761))\n"
                + " from \"expense_fact\"";
        final String expected = "SELECT CAST(\"store_id\" AS VARCHAR(256))\n"
                + "FROM \"foodmart\".\"expense_fact\"";
        sql( query ).withPostgresqlModifiedTypeSystem().ok( expected );
    }


    /**
     * Test case for "JDBC adapter may generate casts on PostgreSQL for VARCHAR type exceeding max length".
     */
    @Test
    public void testCastLongVarchar2() {
        final String query = "select cast(\"store_id\" as VARCHAR(175))\n"
                + " from \"expense_fact\"";
        final String expected = "SELECT CAST(\"store_id\" AS VARCHAR(175))\n"
                + "FROM \"foodmart\".\"expense_fact\"";
        sql( query ).withPostgresqlModifiedTypeSystem().ok( expected );
    }


    /**
     * Test case for "When generating SQL, translate SUM0(x) to COALESCE(SUM(x), 0)".
     */
    @Test
    public void testSum0BecomesCoalesce() {
        final AlgBuilder builder = algBuilder();
        final AlgNode root = builder
                .scan( "emp" )
                .aggregate( builder.groupKey(), builder.aggregateCall( OperatorRegistry.getAgg( OperatorName.SUM0 ), builder.field( 3 ) ).as( "s" ) )
                .build();
        final String expectedMysql = "SELECT COALESCE(SUM(`mgr`), 0) AS `s`\n"
                + "FROM `scott`.`emp`";
        assertThat( toSql( root, DatabaseProduct.MYSQL.getDialect() ), Matchers.isLinux( expectedMysql ) );
        final String expectedPostgresql = "SELECT COALESCE(SUM(\"mgr\"), 0) AS \"s\"\n"
                + "FROM \"scott\".\"emp\"";
        assertThat( toSql( root, DatabaseProduct.POSTGRESQL.getDialect() ), Matchers.isLinux( expectedPostgresql ) );
    }


    /**
     * As {@link #testSum0BecomesCoalesce()} but for windowed aggregates.
     */
    @Test
    @Ignore("Unnecessary casts")
    public void testWindowedSum0BecomesCoalesce() {
        final String query = "select\n"
                + "  AVG(\"net_weight\") OVER (order by \"product_id\" rows 3 preceding)\n"
                + "from \"foodmart\".\"product\"";
        final String expectedPostgresql = "SELECT CASE WHEN (COUNT(\"net_weight\") OVER (ORDER BY \"product_id\" ROWS BETWEEN 3 PRECEDING AND CURRENT ROW)) > 0 "
                + "THEN CAST(COALESCE(SUM(\"net_weight\") OVER (ORDER BY \"product_id\" ROWS BETWEEN 3 PRECEDING AND CURRENT ROW), 0) AS DOUBLE PRECISION) "
                + "ELSE NULL END / (COUNT(\"net_weight\") OVER (ORDER BY \"product_id\" ROWS BETWEEN 3 PRECEDING AND CURRENT ROW))\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query )
                .withPostgresql()
                .ok( expectedPostgresql );
    }


    /**
     * Test case for "JDBC adapter should generate sub-SELECT if dialect does not support nested aggregate functions".
     */
    @Test
    public void testNestedAggregates() {
        // PostgreSQL, MySQL, Vertica do not support nested aggregate functions, so for these, the JDBC adapter generates a SELECT in the FROM clause. Oracle can do it in a single SELECT.
        final String query = "select\n"
                + "    SUM(\"net_weight1\") as \"net_weight_converted\"\n"
                + "  from ("
                + "    select\n"
                + "       SUM(\"net_weight\") as \"net_weight1\"\n"
                + "    from \"foodmart\".\"product\"\n"
                + "    group by \"product_id\")";
        final String expectedOracle = "SELECT SUM(SUM(\"net_weight\")) \"net_weight_converted\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_id\"";
        final String expectedMySQL = "SELECT SUM(`net_weight1`) AS `net_weight_converted`\n"
                + "FROM (SELECT SUM(`net_weight`) AS `net_weight1`\n"
                + "FROM `foodmart`.`product`\n"
                + "GROUP BY `product_id`) AS `t1`";
        final String expectedVertica = "SELECT SUM(\"net_weight1\") AS \"net_weight_converted\"\n"
                + "FROM (SELECT SUM(\"net_weight\") AS \"net_weight1\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_id\") AS \"t1\"";
        final String expectedPostgresql = "SELECT SUM(\"net_weight1\") AS \"net_weight_converted\"\n"
                + "FROM (SELECT SUM(\"net_weight\") AS \"net_weight1\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_id\") AS \"t1\"";
        sql( query )
                .withOracle()
                .ok( expectedOracle )
                .withMysql()
                .ok( expectedMySQL )
                .withVertica()
                .ok( expectedVertica )
                .withPostgresql()
                .ok( expectedPostgresql );
    }


    /**
     * Test case for "JDBC adapter throws NullPointerException while generating GROUP BY query for MySQL".
     *
     * MySQL does not support nested aggregates, so {@link AlgToSqlConverter} performs some extra checks, looking for aggregates in the input sub-query, and these would fail with {@code NullPointerException}
     * and {@code ClassCastException} in some cases.
     */
    @Test
    public void testNestedAggregatesMySqlTable() {
        final AlgBuilder builder = algBuilder();
        final AlgNode root = builder
                .scan( "emp" )
                .aggregate( builder.groupKey(), builder.count( false, "c", builder.field( 3 ) ) )
                .build();
        final SqlDialect dialect = DatabaseProduct.MYSQL.getDialect();
        final String expectedSql = "SELECT COUNT(`mgr`) AS `c`\n"
                + "FROM `scott`.`emp`";
        assertThat( toSql( root, dialect ), Matchers.isLinux( expectedSql ) );
    }


    /**
     * As {@link #testNestedAggregatesMySqlTable()}, but input is a sub-query, not a table.
     */
    @Test
    public void testNestedAggregatesMySqlStar() {
        final AlgBuilder builder = algBuilder();
        final AlgNode root = builder
                .scan( "emp" )
                .filter( builder.equals( builder.field( "deptno" ), builder.literal( 10 ) ) )
                .aggregate( builder.groupKey(), builder.count( false, "c", builder.field( 3 ) ) )
                .build();
        final SqlDialect dialect = DatabaseProduct.MYSQL.getDialect();
        final String expectedSql = "SELECT COUNT(`mgr`) AS `c`\n"
                + "FROM `scott`.`emp`\n"
                + "WHERE `deptno` = 10";
        assertThat( toSql( root, dialect ), Matchers.isLinux( expectedSql ) );
    }


    @Test
    public void testSelectQueryWithGroupByAndProjectList1() {
        String query = "select count(*)  from \"product\" group by \"product_class_id\", \"product_id\"";

        final String expected = "SELECT COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\", \"product_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithGroupByHaving() {
        String query = "select count(*) from \"product\" group by \"product_class_id\", \"product_id\"  having \"product_id\"  > 10";
        final String expected = "SELECT COUNT(*)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\", \"product_id\"\n"
                + "HAVING \"product_id\" > 10";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "Aggregates and having cannot be combined".
     */
    @Test
    public void testSelectQueryWithGroupByHaving2() {
        String query = " select \"product\".\"product_id\",\n"
                + "    min(\"sales_fact_1997\".\"store_id\")\n"
                + "    from \"product\"\n"
                + "    inner join \"sales_fact_1997\"\n"
                + "    on \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
                + "    group by \"product\".\"product_id\"\n"
                + "    having count(*) > 1";

        String expected = "SELECT \"product\".\"product_id\", "
                + "MIN(\"sales_fact_1997\".\"store_id\")\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "INNER JOIN \"foodmart\".\"sales_fact_1997\" "
                + "ON \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
                + "GROUP BY \"product\".\"product_id\"\n"
                + "HAVING COUNT(*) > 1";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "Aggregates and having cannot be combined".
     */
    @Test
    public void testSelectQueryWithGroupByHaving3() {
        String query = " select * from (select \"product\".\"product_id\",\n"
                + "    min(\"sales_fact_1997\".\"store_id\")\n"
                + "    from \"product\"\n"
                + "    inner join \"sales_fact_1997\"\n"
                + "    on \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
                + "    group by \"product\".\"product_id\"\n"
                + "    having count(*) > 1) where \"product_id\" > 100";

        String expected = "SELECT \"product_id\", MIN(\"sales_fact_1997\".\"store_id\")\n"
                + "FROM (SELECT \"product\".\"product_id\", MIN(\"sales_fact_1997\".\"store_id\")\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "INNER JOIN \"foodmart\".\"sales_fact_1997\" ON \"product\".\"product_id\" = \"sales_fact_1997\".\"product_id\"\n"
                + "GROUP BY \"product\".\"product_id\"\n"
                + "HAVING COUNT(*) > 1) AS \"t2\"\n"
                + "WHERE \"t2\".\"product_id\" > 100";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithOrderByClause() {
        String query = "select \"product_id\"  from \"product\" order by \"net_weight\"";
        final String expected = "SELECT \"product_id\", \"net_weight\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"net_weight\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithOrderByClause1() {
        String query = "select \"product_id\", \"net_weight\" from \"product\" order by \"net_weight\"";
        final String expected = "SELECT \"product_id\", \"net_weight\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"net_weight\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithTwoOrderByClause() {
        String query = "select \"product_id\"  from \"product\" order by \"net_weight\", \"gross_weight\"";
        final String expected = "SELECT \"product_id\", \"net_weight\", \"gross_weight\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"net_weight\", \"gross_weight\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithAscDescOrderByClause() {
        String query = "select \"product_id\" from \"product\" order by \"net_weight\" asc, \"gross_weight\" desc, \"low_fat\"";
        final String expected = "SELECT"
                + " \"product_id\", \"net_weight\", \"gross_weight\", \"low_fat\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"net_weight\", \"gross_weight\" DESC, \"low_fat\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testHiveSelectCharset() {
        String query = "select \"hire_date\", cast(\"hire_date\" as varchar(10)) from \"foodmart\".\"reserve_employee\"";
        final String expected = "SELECT hire_date, CAST(hire_date AS VARCHAR(10))\n"
                + "FROM foodmart.reserve_employee";
        sql( query ).withHive().ok( expected );
    }


    /**
     * Test case for "MS SQL Server does not support character set as part of data type".
     */
    @Test
    public void testMssqlCharacterSet() {
        String query = "select \"hire_date\", cast(\"hire_date\" as varchar(10))\n"
                + "from \"foodmart\".\"reserve_employee\"";
        final String expected = "SELECT [hire_date], CAST([hire_date] AS VARCHAR(10))\n"
                + "FROM [foodmart].[reserve_employee]";
        sql( query ).withMssql().ok( expected );
    }


    /**
     * Tests that IN can be un-parsed.
     *
     * This cannot be tested using "sql", because because Polypheny-DB's SQL parser replaces INs with ORs or sub-queries.
     */
    @Test
    public void testUnparseIn1() {
        final AlgBuilder builder = algBuilder().scan( "emp" );
        final RexNode condition = builder.call( OperatorRegistry.get( OperatorName.IN ), builder.field( "deptno" ), builder.literal( 21 ) );
        final AlgNode root = algBuilder().scan( "emp" ).filter( condition ).build();
        final String sql = toSql( root );
        final String expectedSql = "SELECT *\n"
                + "FROM \"scott\".\"emp\"\n"
                + "WHERE \"deptno\" IN (21)";
        assertThat( sql, Matchers.isLinux( expectedSql ) );
    }


    @Test
    public void testUnparseIn2() {
        final AlgBuilder builder = algBuilder();
        final AlgNode alg = builder
                .scan( "emp" )
                .filter( builder.call( OperatorRegistry.get( OperatorName.IN ), builder.field( "deptno" ), builder.literal( 20 ), builder.literal( 21 ) ) )
                .build();
        final String sql = toSql( alg );
        final String expectedSql = "SELECT *\n"
                + "FROM \"scott\".\"emp\"\n"
                + "WHERE \"deptno\" IN (20, 21)";
        assertThat( sql, Matchers.isLinux( expectedSql ) );
    }


    @Test
    public void testUnparseInStruct1() {
        final AlgBuilder builder = algBuilder().scan( "emp" );
        final RexNode condition = builder.call(
                OperatorRegistry.get( OperatorName.IN ),
                builder.call( OperatorRegistry.get( OperatorName.ROW ), builder.field( "deptno" ), builder.field( "job" ) ),
                builder.call( OperatorRegistry.get( OperatorName.ROW ), builder.literal( 1 ), builder.literal( "president" ) ) );
        final AlgNode root = algBuilder().scan( "emp" ).filter( condition ).build();
        final String sql = toSql( root );
        final String expectedSql = "SELECT *\n"
                + "FROM \"scott\".\"emp\"\n"
                + "WHERE ROW(\"deptno\", \"job\") IN (ROW(1, 'president'))";
        assertThat( sql, Matchers.isLinux( expectedSql ) );
    }


    @Test
    public void testUnparseInStruct2() {
        final AlgBuilder builder = algBuilder().scan( "emp" );
        final RexNode condition =
                builder.call(
                        OperatorRegistry.get( OperatorName.IN ),
                        builder.call( OperatorRegistry.get( OperatorName.ROW ), builder.field( "deptno" ), builder.field( "job" ) ),
                        builder.call( OperatorRegistry.get( OperatorName.ROW ), builder.literal( 1 ), builder.literal( "president" ) ),
                        builder.call( OperatorRegistry.get( OperatorName.ROW ), builder.literal( 2 ), builder.literal( "president" ) ) );
        final AlgNode root = algBuilder().scan( "emp" ).filter( condition ).build();
        final String sql = toSql( root );
        final String expectedSql = "SELECT *\n"
                + "FROM \"scott\".\"emp\"\n"
                + "WHERE ROW(\"deptno\", \"job\") IN (ROW(1, 'president'), ROW(2, 'president'))";
        assertThat( sql, Matchers.isLinux( expectedSql ) );
    }


    @Test
    public void testSelectQueryWithLimitClause() {
        String query = "select \"product_id\"  from \"product\" limit 100 offset 10";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "LIMIT 100\nOFFSET 10";
        sql( query ).withHive().ok( expected );
    }


    @Test
    public void testPositionFunctionForHive() {
        final String query = "select position('A' IN 'ABC') from \"product\"";
        final String expected = "SELECT INSTR('ABC', 'A')\n"
                + "FROM foodmart.product";
        sql( query ).withHive().ok( expected );
    }


    @Test
    public void testPositionFunctionForBigQuery() {
        final String query = "select position('A' IN 'ABC') from \"product\"";
        final String expected = "SELECT STRPOS('ABC', 'A')\n"
                + "FROM foodmart.product";
        sql( query ).withBigquery().ok( expected );
    }


    @Test
    public void testModFunctionForHive() {
        final String query = "select mod(11,3) from \"product\"";
        final String expected = "SELECT 11 % 3\n"
                + "FROM foodmart.product";
        sql( query ).withHive().ok( expected );
    }


    @Test
    public void testUnionOperatorForBigQuery() {
        final String query = "select mod(11,3) from \"product\"\n"
                + "UNION select 1 from \"product\"";
        final String expected = "SELECT MOD(11, 3)\n"
                + "FROM foodmart.product\n"
                + "UNION DISTINCT\nSELECT 1\nFROM foodmart.product";
        sql( query ).withBigquery().ok( expected );
    }


    @Test
    public void testIntersectOperatorForBigQuery() {
        final String query = "select mod(11,3) from \"product\"\n"
                + "INTERSECT select 1 from \"product\"";
        final String expected = "SELECT MOD(11, 3)\n"
                + "FROM foodmart.product\n"
                + "INTERSECT DISTINCT\nSELECT 1\nFROM foodmart.product";
        sql( query ).withBigquery().ok( expected );
    }


    @Test
    public void testExceptOperatorForBigQuery() {
        final String query = "select mod(11,3) from \"product\"\n"
                + "EXCEPT select 1 from \"product\"";
        final String expected = "SELECT MOD(11, 3)\n"
                + "FROM foodmart.product\n"
                + "EXCEPT DISTINCT\nSELECT 1\nFROM foodmart.product";
        sql( query ).withBigquery().ok( expected );
    }


    @Test
    public void testHiveSelectQueryWithOrderByDescAndNullsFirstShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "ORDER BY product_id IS NULL DESC, product_id DESC";
        sql( query ).dialect( HiveSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testHiveSelectQueryWithOrderByAscAndNullsLastShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls last";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "ORDER BY product_id IS NULL, product_id";
        sql( query ).dialect( HiveSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testHiveSelectQueryWithOrderByAscNullsFirstShouldNotAddNullEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls first";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "ORDER BY product_id";
        sql( query ).dialect( HiveSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testHiveSelectQueryWithOrderByDescNullsLastShouldNotAddNullEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls last";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "ORDER BY product_id DESC";
        sql( query ).dialect( HiveSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testMysqlCastToBigint() {
        // MySQL does not allow cast to BIGINT; instead cast to SIGNED.
        final String query = "select cast(\"product_id\" as bigint) from \"product\"";
        final String expected = "SELECT CAST(`product_id` AS SIGNED)\n"
                + "FROM `foodmart`.`product`";
        sql( query ).withMysql().ok( expected );
    }


    @Test
    public void testMysqlCastToInteger() {
        // MySQL does not allow cast to INTEGER; instead cast to SIGNED.
        final String query = "select \"employee_id\",\n"
                + "  cast(\"salary_paid\" * 10000 as integer)\n"
                + "from \"salary\"";
        final String expected = "SELECT `employee_id`,"
                + " CAST(`salary_paid` * 10000 AS SIGNED)\n"
                + "FROM `foodmart`.`salary`";
        sql( query ).withMysql().ok( expected );
    }


    @Test
    public void testHiveSelectQueryWithOrderByDescAndHighNullsWithVersionGreaterThanOrEq21() {
        final HiveSqlDialect hive2_1Dialect =
                new HiveSqlDialect( SqlDialect.EMPTY_CONTEXT
                        .withDatabaseMajorVersion( 2 )
                        .withDatabaseMinorVersion( 1 )
                        .withNullCollation( NullCollation.LOW ) );

        final HiveSqlDialect hive2_2_Dialect =
                new HiveSqlDialect( SqlDialect.EMPTY_CONTEXT
                        .withDatabaseMajorVersion( 2 )
                        .withDatabaseMinorVersion( 2 )
                        .withNullCollation( NullCollation.LOW ) );

        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "ORDER BY product_id DESC NULLS FIRST";
        sql( query ).dialect( hive2_1Dialect ).ok( expected );
        sql( query ).dialect( hive2_2_Dialect ).ok( expected );
    }


    @Test
    public void testHiveSelectQueryWithOrderByDescAndHighNullsWithVersion20() {
        final HiveSqlDialect hive2_1_0_Dialect =
                new HiveSqlDialect( SqlDialect.EMPTY_CONTEXT
                        .withDatabaseMajorVersion( 2 )
                        .withDatabaseMinorVersion( 0 )
                        .withNullCollation( NullCollation.LOW ) );
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT product_id\n"
                + "FROM foodmart.product\n"
                + "ORDER BY product_id IS NULL DESC, product_id DESC";
        sql( query ).dialect( hive2_1_0_Dialect ).ok( expected );
    }


    @Test
    public void testJethroDataSelectQueryWithOrderByDescAndNullsFirstShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";

        final String expected = "SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"product_id\", \"product_id\" DESC";
        sql( query ).dialect( jethroDataSqlDialect() ).ok( expected );
    }


    @Test
    public void testMySqlSelectQueryWithOrderByDescAndNullsFirstShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL DESC, `product_id` DESC";
        sql( query ).dialect( MysqlSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testMySqlSelectQueryWithOrderByAscAndNullsLastShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL, `product_id`";
        sql( query ).dialect( MysqlSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testMySqlSelectQueryWithOrderByAscNullsFirstShouldNotAddNullEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id`";
        sql( query ).dialect( MysqlSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testMySqlSelectQueryWithOrderByDescNullsLastShouldNotAddNullEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` DESC";
        sql( query ).dialect( MysqlSqlDialect.DEFAULT ).ok( expected );
    }


    @Test
    public void testMySqlWithHighNullsSelectWithOrderByAscNullsLastAndNoEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id`";
        sql( query ).dialect( mySqlDialect( NullCollation.HIGH ) ).ok( expected );
    }


    @Test
    public void testMySqlWithHighNullsSelectWithOrderByAscNullsFirstAndNullEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL DESC, `product_id`";
        sql( query ).dialect( mySqlDialect( NullCollation.HIGH ) ).ok( expected );
    }


    @Test
    public void testMySqlWithHighNullsSelectWithOrderByDescNullsFirstAndNoEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` DESC";
        sql( query ).dialect( mySqlDialect( NullCollation.HIGH ) ).ok( expected );
    }


    @Test
    public void testMySqlWithHighNullsSelectWithOrderByDescNullsLastAndNullEmulation() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL, `product_id` DESC";
        sql( query ).dialect( mySqlDialect( NullCollation.HIGH ) ).ok( expected );
    }


    @Test
    public void testMySqlWithFirstNullsSelectWithOrderByDescAndNullsFirstShouldNotBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` DESC";
        sql( query ).dialect( mySqlDialect( NullCollation.FIRST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithFirstNullsSelectWithOrderByAscAndNullsFirstShouldNotBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id`";
        sql( query ).dialect( mySqlDialect( NullCollation.FIRST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithFirstNullsSelectWithOrderByDescAndNullsLastShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL, `product_id` DESC";
        sql( query ).dialect( mySqlDialect( NullCollation.FIRST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithFirstNullsSelectWithOrderByAscAndNullsLastShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL, `product_id`";
        sql( query ).dialect( mySqlDialect( NullCollation.FIRST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithLastNullsSelectWithOrderByDescAndNullsFirstShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL DESC, `product_id` DESC";
        sql( query ).dialect( mySqlDialect( NullCollation.LAST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithLastNullsSelectWithOrderByAscAndNullsFirstShouldBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls first";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` IS NULL DESC, `product_id`";
        sql( query ).dialect( mySqlDialect( NullCollation.LAST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithLastNullsSelectWithOrderByDescAndNullsLastShouldNotBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" desc nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id` DESC";
        sql( query ).dialect( mySqlDialect( NullCollation.LAST ) ).ok( expected );
    }


    @Test
    public void testMySqlWithLastNullsSelectWithOrderByAscAndNullsLastShouldNotBeEmulated() {
        final String query = "select \"product_id\" from \"product\"\n"
                + "order by \"product_id\" nulls last";
        final String expected = "SELECT `product_id`\n"
                + "FROM `foodmart`.`product`\n"
                + "ORDER BY `product_id`";
        sql( query ).dialect( mySqlDialect( NullCollation.LAST ) ).ok( expected );
    }


    @Test
    public void testSelectQueryWithLimitClauseWithoutOrder() {
        String query = "select \"product_id\"  from \"product\" limit 100 offset 10";
        final String expected = "SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "OFFSET 10 ROWS\n"
                + "FETCH NEXT 100 ROWS ONLY";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithLimitOffsetClause() {
        String query = "select \"product_id\"  from \"product\" order by \"net_weight\" asc limit 100 offset 10";
        final String expected = "SELECT \"product_id\", \"net_weight\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"net_weight\"\n"
                + "OFFSET 10 ROWS\n"
                + "FETCH NEXT 100 ROWS ONLY";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithParameters() {
        String query = "select * from \"product\" where \"product_id\" = ? AND ? >= \"shelf_width\"";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_id\" = CAST(? AS INTEGER) AND CAST(? AS INTEGER) >= \"shelf_width\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithFetchOffsetClause() {
        String query = "select \"product_id\"  from \"product\" order by \"product_id\" offset 10 rows fetch next 100 rows only";
        final String expected = "SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "ORDER BY \"product_id\"\n"
                + "OFFSET 10 ROWS\n"
                + "FETCH NEXT 100 ROWS ONLY";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryComplex() {
        String query = "select count(*), \"units_per_case\" from \"product\" where \"cases_per_pallet\" > 100 group by \"product_id\", \"units_per_case\" order by \"units_per_case\" desc";
        final String expected = "SELECT COUNT(*), \"units_per_case\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"cases_per_pallet\" > 100\n"
                + "GROUP BY \"product_id\", \"units_per_case\"\n"
                + "ORDER BY \"units_per_case\" DESC";
        sql( query ).ok( expected );
    }


    @Test
    public void testSelectQueryWithGroup() {
        String query = "select count(*), sum(\"employee_id\") from \"reserve_employee\" "
                + "where \"hire_date\" > '2015-01-01' and (\"position_title\" = 'SDE' or \"position_title\" = 'SDM') "
                + "group by \"store_id\", \"position_title\"";
        final String expected = "SELECT COUNT(*), SUM(\"employee_id\")\n"
                + "FROM \"foodmart\".\"reserve_employee\"\n"
                + "WHERE \"hire_date\" > '2015-01-01' "
                + "AND (\"position_title\" = 'SDE' OR \"position_title\" = 'SDM')\n"
                + "GROUP BY \"store_id\", \"position_title\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testSimpleJoin() {
        String query = "select *\n"
                + "from \"sales_fact_1997\" as s\n"
                + "join \"customer\" as c on s.\"customer_id\" = c.\"customer_id\"\n"
                + "join \"product\" as p on s.\"product_id\" = p.\"product_id\"\n"
                + "join \"product_class\" as pc\n"
                + "  on p.\"product_class_id\" = pc.\"product_class_id\"\n"
                + "where c.\"city\" = 'San Francisco'\n"
                + "and pc.\"product_department\" = 'Snacks'\n";
        final String expected =
                "SELECT \"sales_fact_1997\".\"product_id\", \"sales_fact_1997\".\"time_id\", \"sales_fact_1997\".\"customer_id\", \"sales_fact_1997\".\"promotion_id\", \"sales_fact_1997\".\"store_id\", \"sales_fact_1997\".\"store_sales\", \"sales_fact_1997\".\"store_cost\", \"sales_fact_1997\".\"unit_sales\", \"customer\".\"customer_id\" AS \"customer_id0\", \"customer\".\"account_num\", \"customer\".\"lname\", \"customer\".\"fname\", \"customer\".\"mi\", \"customer\".\"address1\", \"customer\".\"address2\", \"customer\".\"address3\", \"customer\".\"address4\", \"customer\".\"city\", \"customer\".\"state_province\", \"customer\".\"postal_code\", \"customer\".\"country\", \"customer\".\"customer_region_id\", \"customer\".\"phone1\", \"customer\".\"phone2\", \"customer\".\"birthdate\", \"customer\".\"marital_status\", \"customer\".\"yearly_income\", \"customer\".\"gender\", \"customer\".\"total_children\", \"customer\".\"num_children_at_home\", \"customer\".\"education\", \"customer\".\"date_accnt_opened\", \"customer\".\"member_card\", \"customer\".\"occupation\", \"customer\".\"houseowner\", \"customer\".\"num_cars_owned\", \"customer\".\"fullname\", \"product\".\"product_class_id\", \"product\".\"product_id\" AS \"product_id0\", \"product\".\"brand_name\", \"product\".\"product_name\", \"product\".\"SKU\", \"product\".\"SRP\", \"product\".\"gross_weight\", \"product\".\"net_weight\", \"product\".\"recyclable_package\", \"product\".\"low_fat\", \"product\".\"units_per_case\", \"product\".\"cases_per_pallet\", \"product\".\"shelf_width\", \"product\".\"shelf_height\", \"product\".\"shelf_depth\", \"product_class\".\"product_class_id\" AS \"product_class_id0\", \"product_class\".\"product_subcategory\", \"product_class\".\"product_category\", \"product_class\".\"product_department\", \"product_class\".\"product_family\"\n"
                        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                        + "INNER JOIN \"foodmart\".\"customer\" "
                        + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\""
                        + ".\"customer_id\"\n"
                        + "INNER JOIN \"foodmart\".\"product\" "
                        + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\"\n"
                        + "INNER JOIN \"foodmart\".\"product_class\" "
                        + "ON \"product\".\"product_class_id\" = \"product_class\""
                        + ".\"product_class_id\"\n"
                        + "WHERE \"customer\".\"city\" = 'San Francisco' AND "
                        + "\"product_class\".\"product_department\" = 'Snacks'";
        sql( query ).ok( expected );
    }


    @Test
    public void testSimpleJoinUsing() {
        String query = "select *\n"
                + "from \"sales_fact_1997\" as s\n"
                + "  join \"customer\" as c using (\"customer_id\")\n"
                + "  join \"product\" as p using (\"product_id\")\n"
                + "  join \"product_class\" as pc using (\"product_class_id\")\n"
                + "where c.\"city\" = 'San Francisco'\n"
                + "and pc.\"product_department\" = 'Snacks'\n";
        final String expected = "SELECT"
                + " \"product\".\"product_class_id\","
                + " \"sales_fact_1997\".\"product_id\","
                + " \"sales_fact_1997\".\"customer_id\","
                + " \"sales_fact_1997\".\"time_id\","
                + " \"sales_fact_1997\".\"promotion_id\","
                + " \"sales_fact_1997\".\"store_id\","
                + " \"sales_fact_1997\".\"store_sales\","
                + " \"sales_fact_1997\".\"store_cost\","
                + " \"sales_fact_1997\".\"unit_sales\","
                + " \"customer\".\"account_num\","
                + " \"customer\".\"lname\","
                + " \"customer\".\"fname\","
                + " \"customer\".\"mi\","
                + " \"customer\".\"address1\","
                + " \"customer\".\"address2\","
                + " \"customer\".\"address3\","
                + " \"customer\".\"address4\","
                + " \"customer\".\"city\","
                + " \"customer\".\"state_province\","
                + " \"customer\".\"postal_code\","
                + " \"customer\".\"country\","
                + " \"customer\".\"customer_region_id\","
                + " \"customer\".\"phone1\","
                + " \"customer\".\"phone2\","
                + " \"customer\".\"birthdate\","
                + " \"customer\".\"marital_status\","
                + " \"customer\".\"yearly_income\","
                + " \"customer\".\"gender\","
                + " \"customer\".\"total_children\","
                + " \"customer\".\"num_children_at_home\","
                + " \"customer\".\"education\","
                + " \"customer\".\"date_accnt_opened\","
                + " \"customer\".\"member_card\","
                + " \"customer\".\"occupation\","
                + " \"customer\".\"houseowner\","
                + " \"customer\".\"num_cars_owned\","
                + " \"customer\".\"fullname\","
                + " \"product\".\"brand_name\","
                + " \"product\".\"product_name\","
                + " \"product\".\"SKU\","
                + " \"product\".\"SRP\","
                + " \"product\".\"gross_weight\","
                + " \"product\".\"net_weight\","
                + " \"product\".\"recyclable_package\","
                + " \"product\".\"low_fat\","
                + " \"product\".\"units_per_case\","
                + " \"product\".\"cases_per_pallet\","
                + " \"product\".\"shelf_width\","
                + " \"product\".\"shelf_height\","
                + " \"product\".\"shelf_depth\","
                + " \"product_class\".\"product_subcategory\","
                + " \"product_class\".\"product_category\","
                + " \"product_class\".\"product_department\","
                + " \"product_class\".\"product_family\"\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "INNER JOIN \"foodmart\".\"customer\" ON \"sales_fact_1997\".\"customer_id\" = \"customer\".\"customer_id\"\n"
                + "INNER JOIN \"foodmart\".\"product\" ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\"\n"
                + "INNER JOIN \"foodmart\".\"product_class\" ON \"product\".\"product_class_id\" = \"product_class\".\"product_class_id\"\n"
                + "WHERE \"customer\".\"city\" = 'San Francisco' AND \"product_class\".\"product_department\" = 'Snacks'";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "JDBC adapter generates wrong SQL for self join with sub-query".
     */
    @Test
    public void testSubQueryAlias() {
        String query = "select t1.\"customer_id\", t2.\"customer_id\" \n"
                + "from (select \"customer_id\" from \"sales_fact_1997\") as t1 \n"
                + "inner join (select \"customer_id\" from \"sales_fact_1997\") t2 \n"
                + "on t1.\"customer_id\" = t2.\"customer_id\"";
        final String expected = "SELECT t.customer_id, t0.customer_id AS customer_id0\n"
                + "FROM (SELECT sales_fact_1997.customer_id\n"
                + "FROM foodmart.sales_fact_1997 AS sales_fact_1997) AS t\n"
                + "INNER JOIN (SELECT sales_fact_19970.customer_id\n"
                + "FROM foodmart.sales_fact_1997 AS sales_fact_19970) AS t0 ON t.customer_id = t0.customer_id";

        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testCartesianProductWithCommaSyntax() {
        String query = "select * from \"department\" , \"employee\"";
        String expected = "SELECT \"department\".\"department_id\", \"department\".\"department_description\", \"employee\".\"employee_id\", \"employee\".\"full_name\", \"employee\".\"first_name\", \"employee\".\"last_name\", \"employee\".\"position_id\", \"employee\".\"position_title\", \"employee\".\"store_id\", \"employee\".\"department_id\" AS \"department_id0\", \"employee\".\"birth_date\", \"employee\".\"hire_date\", \"employee\".\"end_date\", \"employee\".\"salary\", \"employee\".\"supervisor_id\", \"employee\".\"education_level\", \"employee\".\"marital_status\", \"employee\".\"gender\", \"employee\".\"management_role\"\n"
                + "FROM \"foodmart\".\"department\",\n"
                + "\"foodmart\".\"employee\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testCartesianProductWithInnerJoinSyntax() {
        String query = "select * from \"department\"\n"
                + "INNER JOIN \"employee\" ON TRUE";
        String expected = "SELECT \"department\".\"department_id\", \"department\".\"department_description\", \"employee\".\"employee_id\", \"employee\".\"full_name\", \"employee\".\"first_name\", \"employee\".\"last_name\", \"employee\".\"position_id\", \"employee\".\"position_title\", \"employee\".\"store_id\", \"employee\".\"department_id\" AS \"department_id0\", \"employee\".\"birth_date\", \"employee\".\"hire_date\", \"employee\".\"end_date\", \"employee\".\"salary\", \"employee\".\"supervisor_id\", \"employee\".\"education_level\", \"employee\".\"marital_status\", \"employee\".\"gender\", \"employee\".\"management_role\"\n"
                + "FROM \"foodmart\".\"department\",\n"
                + "\"foodmart\".\"employee\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testFullJoinOnTrueCondition() {
        String query = "select * from \"department\"\n"
                + "FULL JOIN \"employee\" ON TRUE";
        String expected = "SELECT \"department\".\"department_id\", \"department\".\"department_description\", \"employee\".\"employee_id\", \"employee\".\"full_name\", \"employee\".\"first_name\", \"employee\".\"last_name\", \"employee\".\"position_id\", \"employee\".\"position_title\", \"employee\".\"store_id\", \"employee\".\"department_id\" AS \"department_id0\", \"employee\".\"birth_date\", \"employee\".\"hire_date\", \"employee\".\"end_date\", \"employee\".\"salary\", \"employee\".\"supervisor_id\", \"employee\".\"education_level\", \"employee\".\"marital_status\", \"employee\".\"gender\", \"employee\".\"management_role\"\n"
                + "FROM \"foodmart\".\"department\"\n"
                + "FULL JOIN \"foodmart\".\"employee\" ON TRUE";
        sql( query ).ok( expected );
    }


    @Test
    public void testSimpleIn() {
        String query = "select * from \"department\" where \"department_id\" in (\n"
                + "  select \"department_id\" from \"employee\"\n"
                + "  where \"store_id\" < 150)";
        final String expected = "SELECT "
                + "\"department\".\"department_id\", \"department\""
                + ".\"department_description\"\n"
                + "FROM \"foodmart\".\"department\"\nINNER JOIN "
                + "(SELECT \"department_id\"\nFROM \"foodmart\".\"employee\"\n"
                + "WHERE \"store_id\" < 150\nGROUP BY \"department_id\") AS \"t1\" "
                + "ON \"department\".\"department_id\" = \"t1\".\"department_id\"";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "DB2 should always use aliases for tables: x.y.z AS z".
     */
    @Test
    public void testDb2DialectJoinStar() {
        String query = "select * "
                + "from \"foodmart\".\"employee\" A join \"foodmart\".\"department\" B\n"
                + "on A.\"department_id\" = B.\"department_id\"";
        final String expected = "SELECT employee.employee_id, employee.full_name, employee.first_name, employee.last_name, employee.position_id, employee.position_title, employee.store_id, employee.department_id, employee.birth_date, employee.hire_date, employee.end_date, employee.salary, employee.supervisor_id, employee.education_level, employee.marital_status, employee.gender, employee.management_role, department.department_id AS department_id0, department.department_description\n"
                + "FROM foodmart.employee AS employee\n"
                + "INNER JOIN foodmart.department AS department "
                + "ON employee.department_id = department.department_id";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectSelfJoinStar() {
        String query = "select * "
                + "from \"foodmart\".\"employee\" A join \"foodmart\".\"employee\" B\n"
                + "on A.\"department_id\" = B.\"department_id\"";
        final String expected =
                "SELECT employee.employee_id, employee.full_name, employee.first_name, employee.last_name, employee.position_id, employee.position_title, employee.store_id, employee.department_id, employee.birth_date, employee.hire_date, employee.end_date, employee.salary, employee.supervisor_id, employee.education_level, employee.marital_status, employee.gender, employee.management_role, employee0.employee_id AS employee_id0, employee0.full_name AS full_name0, employee0.first_name AS first_name0, employee0.last_name AS last_name0, employee0.position_id AS position_id0, employee0.position_title AS position_title0, employee0.store_id AS store_id0, employee0.department_id AS department_id0, employee0.birth_date AS birth_date0, employee0.hire_date AS hire_date0, employee0.end_date AS end_date0, employee0.salary AS salary0, employee0.supervisor_id AS supervisor_id0, employee0.education_level AS education_level0, employee0.marital_status AS marital_status0, employee0.gender AS gender0, employee0.management_role AS management_role0\n"
                        + "FROM foodmart.employee AS employee\n"
                        + "INNER JOIN foodmart.employee AS employee0 ON employee.department_id = employee0.department_id";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectJoin() {
        String query = "select A.\"employee_id\", B.\"department_id\" "
                + "from \"foodmart\".\"employee\" A join \"foodmart\".\"department\" B\n"
                + "on A.\"department_id\" = B.\"department_id\"";
        final String expected = "SELECT"
                + " employee.employee_id, department.department_id\n"
                + "FROM foodmart.employee AS employee\n"
                + "INNER JOIN foodmart.department AS department ON employee.department_id = department.department_id";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectSelfJoin() {
        String query = "select A.\"employee_id\", B.\"employee_id\" from \"foodmart\".\"employee\" A join \"foodmart\".\"employee\" B\n"
                + "on A.\"department_id\" = B.\"department_id\"";
        final String expected = "SELECT"
                + " employee.employee_id, employee0.employee_id AS employee_id0\n"
                + "FROM foodmart.employee AS employee\n"
                + "INNER JOIN foodmart.employee AS employee0 ON employee.department_id = employee0.department_id";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectWhere() {
        String query = "select A.\"employee_id\" from \"foodmart\".\"employee\" A where A.\"department_id\" < 1000";
        final String expected = "SELECT employee.employee_id\n"
                + "FROM foodmart.employee AS employee\n"
                + "WHERE employee.department_id < 1000";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectJoinWhere() {
        String query = "select A.\"employee_id\", B.\"department_id\" "
                + "from \"foodmart\".\"employee\" A join \"foodmart\".\"department\" B\n"
                + "on A.\"department_id\" = B.\"department_id\" "
                + "where A.\"employee_id\" < 1000";
        final String expected = "SELECT employee.employee_id, department.department_id\n"
                + "FROM foodmart.employee AS employee\n"
                + "INNER JOIN foodmart.department AS department ON employee.department_id = department.department_id\n"
                + "WHERE employee.employee_id < 1000";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectSelfJoinWhere() {
        String query = "select A.\"employee_id\", B.\"employee_id\" from "
                + "\"foodmart\".\"employee\" A join \"foodmart\".\"employee\" B\n"
                + "on A.\"department_id\" = B.\"department_id\" "
                + "where B.\"employee_id\" < 2000";
        final String expected = "SELECT "
                + "employee.employee_id, employee0.employee_id AS employee_id0\n"
                + "FROM foodmart.employee AS employee\n"
                + "INNER JOIN foodmart.employee AS employee0 ON employee.department_id = employee0.department_id\n"
                + "WHERE employee0.employee_id < 2000";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectCast() {
        String query = "select \"hire_date\", cast(\"hire_date\" as varchar(10)) from \"foodmart\".\"reserve_employee\"";
        final String expected = "SELECT reserve_employee.hire_date, CAST(reserve_employee.hire_date AS VARCHAR(10))\n"
                + "FROM foodmart.reserve_employee AS reserve_employee";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectSelectQueryWithGroupByHaving() {
        String query = "select count(*) from \"product\" group by \"product_class_id\", \"product_id\" having \"product_id\"  > 10";
        final String expected = "SELECT COUNT(*)\n"
                + "FROM foodmart.product AS product\n"
                + "GROUP BY product.product_class_id, product.product_id\n"
                + "HAVING product.product_id > 10";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectSelectQueryComplex() {
        String query = "select count(*), \"units_per_case\" "
                + "from \"product\" where \"cases_per_pallet\" > 100 "
                + "group by \"product_id\", \"units_per_case\" "
                + "order by \"units_per_case\" desc";
        final String expected = "SELECT COUNT(*), product.units_per_case\n"
                + "FROM foodmart.product AS product\n"
                + "WHERE product.cases_per_pallet > 100\n"
                + "GROUP BY product.product_id, product.units_per_case\n"
                + "ORDER BY product.units_per_case DESC";
        sql( query ).withDb2().ok( expected );
    }


    @Test
    public void testDb2DialectSelectQueryWithGroup() {
        String query = "select count(*), sum(\"employee_id\") "
                + "from \"reserve_employee\" "
                + "where \"hire_date\" > '2015-01-01' "
                + "and (\"position_title\" = 'SDE' or \"position_title\" = 'SDM') "
                + "group by \"store_id\", \"position_title\"";
        final String expected = "SELECT"
                + " COUNT(*), SUM(reserve_employee.employee_id)\n"
                + "FROM foodmart.reserve_employee AS reserve_employee\n"
                + "WHERE reserve_employee.hire_date > '2015-01-01' AND (reserve_employee.position_title = 'SDE' OR reserve_employee.position_title = 'SDM')\n"
                + "GROUP BY reserve_employee.store_id, reserve_employee.position_title";
        sql( query ).withDb2().ok( expected );
    }


    /**
     * Test case for "In JDBC adapter, allow IS NULL and IS NOT NULL operators in generated SQL join condition".
     */
    @Test
    @Ignore
    public void testSimpleJoinConditionWithIsNullOperators() {
        String query = "select *\n"
                + "from \"foodmart\".\"sales_fact_1997\" as \"t1\"\n"
                + "inner join \"foodmart\".\"customer\" as \"t2\"\n"
                + "on \"t1\".\"customer_id\" = \"t2\".\"customer_id\" or "
                + "(\"t1\".\"customer_id\" is null "
                + "and \"t2\".\"customer_id\" is null) or\n"
                + "\"t2\".\"occupation\" is null\n"
                + "inner join \"foodmart\".\"product\" as \"t3\"\n"
                + "on \"t1\".\"product_id\" = \"t3\".\"product_id\" or "
                + "(\"t1\".\"product_id\" is not null or "
                + "\"t3\".\"product_id\" is not null)";
        // Some of the "IS NULL" and "IS NOT NULL" are reduced to TRUE or FALSE,
        // but not all.
        String expected = "SELECT *\nFROM \"foodmart\".\"sales_fact_1997\"\n"
                + "INNER JOIN \"foodmart\".\"customer\" "
                + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\".\"customer_id\""
                + " OR FALSE AND FALSE"
                + " OR \"customer\".\"occupation\" IS NULL\n"
                + "INNER JOIN \"foodmart\".\"product\" "
                + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\""
                + " OR TRUE"
                + " OR TRUE";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "JDBC adapter generates wrong SQL if UNION has more than two inputs".
     */
    @Test
    public void testThreeQueryUnion() {
        String query = "SELECT \"product_id\" FROM \"product\" "
                + " UNION ALL "
                + "SELECT \"product_id\" FROM \"sales_fact_1997\" "
                + " UNION ALL "
                + "SELECT \"product_class_id\" AS product_id FROM \"product_class\"";
        String expected = "SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "UNION ALL\n"
                + "SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "UNION ALL\n"
                + "SELECT \"product_class_id\" AS \"PRODUCT_ID\"\n"
                + "FROM \"foodmart\".\"product_class\"";

        final HepProgram program = new HepProgramBuilder().addRuleClass( UnionMergeRule.class ).build();
        final RuleSet rules = RuleSets.ofList( UnionMergeRule.INSTANCE );
        sql( query )
                .optimize( rules, new HepPlanner( program ) )
                .ok( expected );
    }


    /**
     * Test case for "JDBC adapter fails to SELECT FROM a UNION query".
     */
    @Test
    @Ignore("Unnecessary casts")
    public void testUnionWrappedInASelect() {
        final String query = "select sum(\n"
                + "  case when \"product_id\"=0 then \"net_weight\" else 0 end) as net_weight\n"
                + "from (\n"
                + "  select \"product_id\", \"net_weight\"\n"
                + "  from \"product\"\n"
                + "  union all\n"
                + "  select \"product_id\", 0 as \"net_weight\"\n"
                + "  from \"sales_fact_1997\") t0";
        final String expected = "SELECT SUM(CASE WHEN \"product_id\" = 0"
                + " THEN \"net_weight\" ELSE 0 END) AS \"NET_WEIGHT\"\n"
                + "FROM (SELECT \"product_id\", \"net_weight\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "UNION ALL\n"
                + "SELECT \"product_id\", 0 AS \"net_weight\"\n"
                + "FROM \"foodmart\".\"sales_fact_1997\") AS \"t1\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testLiteral() {
        checkLiteral( "DATE '1978-05-02'" );
        checkLiteral2( "DATE '1978-5-2'", "DATE '1978-05-02'" );
        checkLiteral( "TIME '12:34:56'" );
        checkLiteral( "TIME '12:34:56.78'" );
        checkLiteral2( "TIME '1:4:6.080'", "TIME '01:04:06.080'" );
        checkLiteral( "TIMESTAMP '1978-05-02 12:34:56.78'" );
        checkLiteral2( "TIMESTAMP '1978-5-2 2:4:6.80'", "TIMESTAMP '1978-05-02 02:04:06.80'" );
        checkLiteral( "'I can''t explain'" );
        checkLiteral( "''" );
        checkLiteral( "TRUE" );
        checkLiteral( "123" );
        checkLiteral( "123.45" );
        checkLiteral( "-123.45" );
        checkLiteral( "INTERVAL '1-2' YEAR TO MONTH" );
        checkLiteral( "INTERVAL -'1-2' YEAR TO MONTH" );
        checkLiteral( "INTERVAL '12-11' YEAR TO MONTH" );
        checkLiteral( "INTERVAL '1' YEAR" );
        checkLiteral( "INTERVAL '1' MONTH" );
        checkLiteral( "INTERVAL '12' DAY" );
        checkLiteral( "INTERVAL -'12' DAY" );
        checkLiteral2( "INTERVAL '1 2' DAY TO HOUR", "INTERVAL '1 02' DAY TO HOUR" );
        checkLiteral2( "INTERVAL '1 2:10' DAY TO MINUTE", "INTERVAL '1 02:10' DAY TO MINUTE" );
        checkLiteral2( "INTERVAL '1 2:00' DAY TO MINUTE", "INTERVAL '1 02:00' DAY TO MINUTE" );
        checkLiteral2( "INTERVAL '1 2:34:56' DAY TO SECOND", "INTERVAL '1 02:34:56' DAY TO SECOND" );
        checkLiteral2( "INTERVAL '1 2:34:56.789' DAY TO SECOND", "INTERVAL '1 02:34:56.789' DAY TO SECOND" );
        checkLiteral2( "INTERVAL '1 2:34:56.78' DAY TO SECOND", "INTERVAL '1 02:34:56.78' DAY TO SECOND" );
        checkLiteral2( "INTERVAL '1 2:34:56.078' DAY TO SECOND", "INTERVAL '1 02:34:56.078' DAY TO SECOND" );
        checkLiteral2( "INTERVAL -'1 2:34:56.078' DAY TO SECOND", "INTERVAL -'1 02:34:56.078' DAY TO SECOND" );
        checkLiteral2( "INTERVAL '1 2:3:5.070' DAY TO SECOND", "INTERVAL '1 02:03:05.07' DAY TO SECOND" );
        checkLiteral( "INTERVAL '1:23' HOUR TO MINUTE" );
        checkLiteral( "INTERVAL '1:02' HOUR TO MINUTE" );
        checkLiteral( "INTERVAL -'1:02' HOUR TO MINUTE" );
        checkLiteral( "INTERVAL '1:23:45' HOUR TO SECOND" );
        checkLiteral( "INTERVAL '1:03:05' HOUR TO SECOND" );
        checkLiteral( "INTERVAL '1:23:45.678' HOUR TO SECOND" );
        checkLiteral( "INTERVAL '1:03:05.06' HOUR TO SECOND" );
        checkLiteral( "INTERVAL '12' MINUTE" );
        checkLiteral( "INTERVAL '12:34' MINUTE TO SECOND" );
        checkLiteral( "INTERVAL '12:34.567' MINUTE TO SECOND" );
        checkLiteral( "INTERVAL '12' SECOND" );
        checkLiteral( "INTERVAL '12.345' SECOND" );
    }


    private void checkLiteral( String expression ) {
        checkLiteral2( expression, expression );
    }


    private void checkLiteral2( String expression, String expected ) {
        sql( "VALUES " + expression )
                .withHsqldb()
                .ok( "SELECT *\n" + "FROM (VALUES  (" + expected + ")) AS \"t\" (\"EXPR$0\")" );
    }


    /**
     * Test case for "Removing Window Boundaries from SqlWindow of Aggregate Function which do not allow Framing".
     */
    @Test
    public void testRowNumberFunctionForPrintingOfFrameBoundary() {
        String query = "SELECT row_number() over (order by \"hire_date\") FROM \"employee\"";
        String expected = "SELECT ROW_NUMBER() OVER (ORDER BY \"hire_date\")\n"
                + "FROM \"foodmart\".\"employee\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testRankFunctionForPrintingOfFrameBoundary() {
        String query = "SELECT rank() over (order by \"hire_date\") FROM \"employee\"";
        String expected = "SELECT RANK() OVER (ORDER BY \"hire_date\")\n"
                + "FROM \"foodmart\".\"employee\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testLeadFunctionForPrintingOfFrameBoundary() {
        String query = "SELECT lead(\"employee_id\",1,'NA') over (partition by \"hire_date\" order by \"employee_id\") FROM \"employee\"";
        String expected = "SELECT LEAD(\"employee_id\", 1, 'NA') OVER (PARTITION BY \"hire_date\" ORDER BY \"employee_id\")\n"
                + "FROM \"foodmart\".\"employee\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testLagFunctionForPrintingOfFrameBoundary() {
        String query = "SELECT lag(\"employee_id\",1,'NA') over (partition by \"hire_date\" order by \"employee_id\") FROM \"employee\"";
        String expected = "SELECT LAG(\"employee_id\", 1, 'NA') OVER (PARTITION BY \"hire_date\" ORDER BY \"employee_id\")\n"
                + "FROM \"foodmart\".\"employee\"";
        sql( query ).ok( expected );
    }


    /**
     * Test case for "Generate dialect-specific SQL for FLOOR operator".
     */
    @Test
    public void testFloor() {
        String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
        String expected = "SELECT TRUNC(\"hire_date\", 'MI')\nFROM \"foodmart\".\"employee\"";
        sql( query )
                .withHsqldb()
                .ok( expected );
    }


    @Test
    public void testFloorPostgres() {
        String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
        String expected = "SELECT DATE_TRUNC('MINUTE', \"hire_date\")\nFROM \"foodmart\".\"employee\"";
        sql( query )
                .withPostgresql()
                .ok( expected );
    }


    @Test
    public void testFloorOracle() {
        String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
        String expected = "SELECT TRUNC(\"hire_date\", 'MINUTE')\nFROM \"foodmart\".\"employee\"";
        sql( query )
                .withOracle()
                .ok( expected );
    }


    @Test
    public void testFloorMssqlWeek() {
        String query = "SELECT floor(\"hire_date\" TO WEEK) FROM \"employee\"";
        String expected = "SELECT CONVERT(DATETIME, CONVERT(VARCHAR(10), DATEADD(day, - (6 + DATEPART(weekday, [hire_date] )) % 7, [hire_date] ), 126))\n"
                + "FROM [foodmart].[employee]";
        sql( query )
                .withMssql()
                .ok( expected );
    }


    @Test
    public void testFloorMssqlMonth() {
        String query = "SELECT floor(\"hire_date\" TO MONTH) FROM \"employee\"";
        String expected = "SELECT CONVERT(DATETIME, CONVERT(VARCHAR(7), [hire_date] , 126)+'-01')\n"
                + "FROM [foodmart].[employee]";
        sql( query )
                .withMssql()
                .ok( expected );
    }


    @Test
    public void testFloorMysqlMonth() {
        String query = "SELECT floor(\"hire_date\" TO MONTH) FROM \"employee\"";
        String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-01')\n"
                + "FROM `foodmart`.`employee`";
        sql( query )
                .withMysql()
                .ok( expected );
    }


    @Test
    @Ignore
    public void testUnparseSqlIntervalQualifierDb2() {
        String queryDatePlus = "select  * from \"employee\" where  \"hire_date\" + INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
        String expectedDatePlus = "SELECT *\n"
                + "FROM foodmart.employee AS employee\n"
                + "WHERE (employee.hire_date + 19800 SECOND) > TIMESTAMP '2005-10-17 00:00:00'";

        sql( queryDatePlus )
                .withDb2()
                .ok( expectedDatePlus );

        String queryDateMinus = "select  * from \"employee\" where  \"hire_date\" - INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
        String expectedDateMinus = "SELECT *\n"
                + "FROM foodmart.employee AS employee\n"
                + "WHERE (employee.hire_date - 19800 SECOND) > TIMESTAMP '2005-10-17 00:00:00'";

        sql( queryDateMinus )
                .withDb2()
                .ok( expectedDateMinus );
    }


    @Test
    @Ignore
    public void testUnparseSqlIntervalQualifierMySql() {
        final String sql0 = "select  * from \"employee\" where  \"hire_date\" - INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
        final String expect0 = "SELECT *\n"
                + "FROM `foodmart`.`employee`\n"
                + "WHERE (`hire_date` - INTERVAL '19800' SECOND) > TIMESTAMP '2005-10-17 00:00:00'";
        sql( sql0 ).withMysql().ok( expect0 );

        final String sql1 = "select  * from \"employee\" where  \"hire_date\" + INTERVAL '10' HOUR > TIMESTAMP '2005-10-17 00:00:00' ";
        final String expect1 = "SELECT *\n"
                + "FROM `foodmart`.`employee`\n"
                + "WHERE (`hire_date` + INTERVAL '10' HOUR) > TIMESTAMP '2005-10-17 00:00:00'";
        sql( sql1 ).withMysql().ok( expect1 );

        final String sql2 = "select  * from \"employee\" where  \"hire_date\" + INTERVAL '1-2' year to month > TIMESTAMP '2005-10-17 00:00:00' ";
        final String expect2 = "SELECT *\n"
                + "FROM `foodmart`.`employee`\n"
                + "WHERE (`hire_date` + INTERVAL '1-2' YEAR_MONTH) > TIMESTAMP '2005-10-17 00:00:00'";
        sql( sql2 ).withMysql().ok( expect2 );

        final String sql3 = "select  * from \"employee\" where  \"hire_date\" + INTERVAL '39:12' MINUTE TO SECOND > TIMESTAMP '2005-10-17 00:00:00' ";
        final String expect3 = "SELECT *\n"
                + "FROM `foodmart`.`employee`\n"
                + "WHERE (`hire_date` + INTERVAL '39:12' MINUTE_SECOND) > TIMESTAMP '2005-10-17 00:00:00'";
        sql( sql3 ).withMysql().ok( expect3 );
    }


    @Test
    @Ignore
    public void testUnparseSqlIntervalQualifierMsSql() {
        String queryDatePlus = "select  * from \"employee\" where  \"hire_date\" + INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
        String expectedDatePlus = "SELECT *\n"
                + "FROM [foodmart].[employee]\n"
                + "WHERE DATEADD(SECOND, 19800, [hire_date]) > '2005-10-17 00:00:00'";

        sql( queryDatePlus )
                .withMssql()
                .ok( expectedDatePlus );

        String queryDateMinus = "select  * from \"employee\" where  \"hire_date\" - INTERVAL '19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
        String expectedDateMinus = "SELECT *\n"
                + "FROM [foodmart].[employee]\n"
                + "WHERE DATEADD(SECOND, -19800, [hire_date]) > '2005-10-17 00:00:00'";

        sql( queryDateMinus )
                .withMssql()
                .ok( expectedDateMinus );

        String queryDateMinusNegate = "select  * from \"employee\" "
                + "where  \"hire_date\" -INTERVAL '-19800' SECOND(5) > TIMESTAMP '2005-10-17 00:00:00' ";
        String expectedDateMinusNegate = "SELECT *\n"
                + "FROM [foodmart].[employee]\n"
                + "WHERE DATEADD(SECOND, 19800, [hire_date]) > '2005-10-17 00:00:00'";

        sql( queryDateMinusNegate )
                .withMssql()
                .ok( expectedDateMinusNegate );
    }


    @Test
    public void testFloorMysqlWeek() {
        String query = "SELECT floor(\"hire_date\" TO WEEK) FROM \"employee\"";
        String expected = "SELECT STR_TO_DATE(DATE_FORMAT(`hire_date` , '%x%v-1'), '%x%v-%w')\n"
                + "FROM `foodmart`.`employee`";
        sql( query )
                .withMysql()
                .ok( expected );
    }


    @Test
    public void testFloorMysqlHour() {
        String query = "SELECT floor(\"hire_date\" TO HOUR) FROM \"employee\"";
        String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:00:00')\n"
                + "FROM `foodmart`.`employee`";
        sql( query )
                .withMysql()
                .ok( expected );
    }


    @Test
    public void testFloorMysqlMinute() {
        String query = "SELECT floor(\"hire_date\" TO MINUTE) FROM \"employee\"";
        String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:00')\n"
                + "FROM `foodmart`.`employee`";
        sql( query )
                .withMysql()
                .ok( expected );
    }


    @Test
    public void testFloorMysqlSecond() {
        String query = "SELECT floor(\"hire_date\" TO SECOND) FROM \"employee\"";
        String expected = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:%s')\n"
                + "FROM `foodmart`.`employee`";
        sql( query )
                .withMysql()
                .ok( expected );
    }


    /**
     * Test case for "JDBC dialect-specific FLOOR fails when in GROUP BY".
     */
    @Test
    public void testFloorWithGroupBy() {
        final String query = "SELECT floor(\"hire_date\" TO MINUTE)\n"
                + "FROM \"employee\"\n"
                + "GROUP BY floor(\"hire_date\" TO MINUTE)";
        final String expected = "SELECT TRUNC(\"hire_date\", 'MI')\n"
                + "FROM \"foodmart\".\"employee\"\n"
                + "GROUP BY TRUNC(\"hire_date\", 'MI')";
        final String expectedOracle = "SELECT TRUNC(\"hire_date\", 'MINUTE')\n"
                + "FROM \"foodmart\".\"employee\"\n"
                + "GROUP BY TRUNC(\"hire_date\", 'MINUTE')";
        final String expectedPostgresql = "SELECT DATE_TRUNC('MINUTE', \"hire_date\")\n"
                + "FROM \"foodmart\".\"employee\"\n"
                + "GROUP BY DATE_TRUNC('MINUTE', \"hire_date\")";
        final String expectedMysql = "SELECT DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:00')\n"
                + "FROM `foodmart`.`employee`\n"
                + "GROUP BY DATE_FORMAT(`hire_date`, '%Y-%m-%d %H:%i:00')";
        sql( query )
                .withHsqldb()
                .ok( expected )
                .withOracle()
                .ok( expectedOracle )
                .withPostgresql()
                .ok( expectedPostgresql )
                .withMysql()
                .ok( expectedMysql );
    }


    @Test
    public void testSubstring() {
        final String query = "select substring(\"brand_name\" from 2) from \"product\"\n";
        final String expectedOracle = "SELECT SUBSTR(\"brand_name\", 2)\n"
                + "FROM \"foodmart\".\"product\"";
        final String expectedPostgresql = "SELECT SUBSTRING(\"brand_name\" FROM 2)\n"
                + "FROM \"foodmart\".\"product\"";
        final String expectedMysql = "SELECT SUBSTRING(`brand_name` FROM 2)\n"
                + "FROM `foodmart`.`product`";
        sql( query )
                .withOracle()
                .ok( expectedOracle )
                .withPostgresql()
                .ok( expectedPostgresql )
                .withMysql()
                .ok( expectedMysql )
                .withMssql()
                // mssql does not support this syntax and so should fail
                .throws_( "MSSQL SUBSTRING requires FROM and FOR arguments" );
    }


    @Test
    public void testSubstringWithFor() {
        final String query = "select substring(\"brand_name\" from 2 for 3) from \"product\"\n";
        final String expectedOracle = "SELECT SUBSTR(\"brand_name\", 2, 3)\n"
                + "FROM \"foodmart\".\"product\"";
        final String expectedPostgresql = "SELECT SUBSTRING(\"brand_name\" FROM 2 FOR 3)\n"
                + "FROM \"foodmart\".\"product\"";
        final String expectedMysql = "SELECT SUBSTRING(`brand_name` FROM 2 FOR 3)\n"
                + "FROM `foodmart`.`product`";
        final String expectedMssql = "SELECT SUBSTRING([brand_name], 2, 3)\n"
                + "FROM [foodmart].[product]";
        sql( query )
                .withOracle()
                .ok( expectedOracle )
                .withPostgresql()
                .ok( expectedPostgresql )
                .withMysql()
                .ok( expectedMysql )
                .withMssql()
                .ok( expectedMssql );
    }


    /**
     * Test case for "Support sub-queries (RexSubQuery) in AlgToSqlConverter".
     */
    @Test
    public void testExistsWithExpand() {
        String query = "select \"product_name\" from \"product\" a "
                + "where exists (select count(*) "
                + "from \"sales_fact_1997\"b "
                + "where b.\"product_id\" = a.\"product_id\")";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE EXISTS (SELECT COUNT(*)\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "WHERE \"product_id\" = \"product\".\"product_id\")";
        sql( query )
                .config( NO_EXPAND_CONFIG )
                .ok( expected );
    }


    @Test
    public void testNotExistsWithExpand() {
        String query = "select \"product_name\" from \"product\" a "
                + "where not exists (select count(*) "
                + "from \"sales_fact_1997\"b "
                + "where b.\"product_id\" = a.\"product_id\")";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE NOT EXISTS (SELECT COUNT(*)\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "WHERE \"product_id\" = \"product\".\"product_id\")";
        sql( query )
                .config( NO_EXPAND_CONFIG )
                .ok( expected );
    }


    @Test
    public void testSubQueryInWithExpand() {
        String query = "select \"product_name\" from \"product\" a "
                + "where \"product_id\" in (select \"product_id\" "
                + "from \"sales_fact_1997\"b "
                + "where b.\"product_id\" = a.\"product_id\")";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_id\" IN (SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "WHERE \"product_id\" = \"product\".\"product_id\")";
        sql( query )
                .config( NO_EXPAND_CONFIG )
                .ok( expected );
    }


    @Test
    public void testSubQueryInWithExpand2() {
        String query = "select \"product_name\" from \"product\" a where \"product_id\" in (1, 2)";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_id\" = 1 OR \"product_id\" = 2";
        sql( query ).config( NO_EXPAND_CONFIG ).ok( expected );
    }


    @Test
    public void testSubQueryNotInWithExpand() {
        String query = "select \"product_name\" from \"product\" a "
                + "where \"product_id\" not in (select \"product_id\" "
                + "from \"sales_fact_1997\"b "
                + "where b.\"product_id\" = a.\"product_id\")";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_id\" NOT IN (SELECT \"product_id\"\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "WHERE \"product_id\" = \"product\".\"product_id\")";
        sql( query )
                .config( NO_EXPAND_CONFIG )
                .ok( expected );
    }


    @Test
    public void testLike() {
        String query = "select \"product_name\" from \"product\" a where \"product_name\" like 'abc'";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_name\" LIKE 'abc'";
        sql( query ).ok( expected );
    }


    @Test
    public void testNotLike() {
        String query = "select \"product_name\" from \"product\" a where \"product_name\" not like 'abc'";
        String expected = "SELECT \"product_name\"\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "WHERE \"product_name\" NOT LIKE 'abc'";
        sql( query ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression() {
        String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    partition by \"product_class_id\", \"brand_name\" \n"
                + "    order by \"product_class_id\" asc, \"brand_name\" desc \n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "PARTITION BY \"product_class_id\", \"brand_name\"\n"
                + "ORDER BY \"product_class_id\", \"brand_name\" DESC\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression2() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+$)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" + $)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression3() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (^strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (^ \"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression4() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (^strt down+ up+$)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (^ \"STRT\" \"DOWN\" + \"UP\" + $)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression5() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down* up?)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" * \"UP\" ?)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression6() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt {-down-} up?)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"

                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" {- \"DOWN\" -} \"UP\" ?)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression7() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down{2} up{3,})\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" { 2 } \"UP\" { 3, })\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression8() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down{,2} up{3,5})\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" { , 2 } \"UP\" { 3, 5 })\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression9() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt {-down+-} {-up*-})\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" {- \"DOWN\" + -} {- \"UP\" * -})\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression10() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (A B C | A C B | B A C | B C A | C A B | C B A)\n"
                + "    define\n"
                + "      A as A.\"net_weight\" < PREV(A.\"net_weight\"),\n"
                + "      B as B.\"net_weight\" > PREV(B.\"net_weight\"),\n"
                + "      C as C.\"net_weight\" < PREV(C.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN "
                + "(\"A\" \"B\" \"C\" | \"A\" \"C\" \"B\" | \"B\" \"A\" \"C\" "
                + "| \"B\" \"C\" \"A\" | \"C\" \"A\" \"B\" | \"C\" \"B\" \"A\")\n"
                + "DEFINE "
                + "\"A\" AS PREV(\"A\".\"net_weight\", 0) < PREV(\"A\".\"net_weight\", 1), "
                + "\"B\" AS PREV(\"B\".\"net_weight\", 0) > PREV(\"B\".\"net_weight\", 1), "
                + "\"C\" AS PREV(\"C\".\"net_weight\", 0) < PREV(\"C\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression11() {
        final String sql = "select *\n"
                + "  from (select * from \"product\") match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression12() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr order by MR.\"net_weight\"";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))\n"
                + "ORDER BY \"net_weight\"";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternExpression13() {
        final String sql = "select *\n"
                + "  from (\n"
                + "select *\n"
                + "from \"sales_fact_1997\" as s\n"
                + "join \"customer\" as c\n"
                + "  on s.\"customer_id\" = c.\"customer_id\"\n"
                + "join \"product\" as p\n"
                + "  on s.\"product_id\" = p.\"product_id\"\n"
                + "join \"product_class\" as pc\n"
                + "  on p.\"product_class_id\" = pc.\"product_class_id\"\n"
                + "where c.\"city\" = 'San Francisco'\n"
                + "and pc.\"product_department\" = 'Snacks'"
                + ") match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr order by MR.\"net_weight\"";
        final String expected =
                "SELECT \"product_id\", \"time_id\", \"customer_id\", \"promotion_id\", \"store_id\", \"store_sales\", \"store_cost\", \"unit_sales\", \"customer_id0\", \"account_num\", \"lname\", \"fname\", \"mi\", \"address1\", \"address2\", \"address3\", \"address4\", \"city\", \"state_province\", \"postal_code\", \"country\", \"customer_region_id\", \"phone1\", \"phone2\", \"birthdate\", \"marital_status\", \"yearly_income\", \"gender\", \"total_children\", \"num_children_at_home\", \"education\", \"date_accnt_opened\", \"member_card\", \"occupation\", \"houseowner\", \"num_cars_owned\", \"fullname\", \"product_class_id\", \"product_id0\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\", \"product_class_id0\", \"product_subcategory\", \"product_category\", \"product_department\", \"product_family\"\n"
                        + "FROM (SELECT \"sales_fact_1997\".\"product_id\", \"sales_fact_1997\".\"time_id\", \"sales_fact_1997\".\"customer_id\", \"sales_fact_1997\".\"promotion_id\", \"sales_fact_1997\".\"store_id\", \"sales_fact_1997\".\"store_sales\", \"sales_fact_1997\".\"store_cost\", \"sales_fact_1997\".\"unit_sales\", \"customer\".\"customer_id\" AS \"customer_id0\", \"customer\".\"account_num\", \"customer\".\"lname\", \"customer\".\"fname\", \"customer\".\"mi\", \"customer\".\"address1\", \"customer\".\"address2\", \"customer\".\"address3\", \"customer\".\"address4\", \"customer\".\"city\", \"customer\".\"state_province\", \"customer\".\"postal_code\", \"customer\".\"country\", \"customer\".\"customer_region_id\", \"customer\".\"phone1\", \"customer\".\"phone2\", \"customer\".\"birthdate\", \"customer\".\"marital_status\", \"customer\".\"yearly_income\", \"customer\".\"gender\", \"customer\".\"total_children\", \"customer\".\"num_children_at_home\", \"customer\".\"education\", \"customer\".\"date_accnt_opened\", \"customer\".\"member_card\", \"customer\".\"occupation\", \"customer\".\"houseowner\", \"customer\".\"num_cars_owned\", \"customer\".\"fullname\", \"product\".\"product_class_id\", \"product\".\"product_id\" AS \"product_id0\", \"product\".\"brand_name\", \"product\".\"product_name\", \"product\".\"SKU\", \"product\".\"SRP\", \"product\".\"gross_weight\", \"product\".\"net_weight\", \"product\".\"recyclable_package\", \"product\".\"low_fat\", \"product\".\"units_per_case\", \"product\".\"cases_per_pallet\", \"product\".\"shelf_width\", \"product\".\"shelf_height\", \"product\".\"shelf_depth\", \"product_class\".\"product_class_id\" AS \"product_class_id0\", \"product_class\".\"product_subcategory\", \"product_class\".\"product_category\", \"product_class\".\"product_department\", \"product_class\".\"product_family\"\n"
                        + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                        + "INNER JOIN \"foodmart\".\"customer\" "
                        + "ON \"sales_fact_1997\".\"customer_id\" = \"customer\".\"customer_id\"\n"
                        + "INNER JOIN \"foodmart\".\"product\" "
                        + "ON \"sales_fact_1997\".\"product_id\" = \"product\".\"product_id\"\n"
                        + "INNER JOIN \"foodmart\".\"product_class\" "
                        + "ON \"product\".\"product_class_id\" = \"product_class\".\"product_class_id\"\n"
                        + "WHERE \"customer\".\"city\" = 'San Francisco' "
                        + "AND \"product_class\".\"product_department\" = 'Snacks') "
                        + "MATCH_RECOGNIZE(\n"
                        + "ONE ROW PER MATCH\n"
                        + "AFTER MATCH SKIP TO NEXT ROW\n"
                        + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                        + "DEFINE "
                        + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                        + "PREV(\"DOWN\".\"net_weight\", 1), "
                        + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                        + "PREV(\"UP\".\"net_weight\", 1))\n"
                        + "ORDER BY \"net_weight\"";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeDefineClause() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeDefineClause2() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < FIRST(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > LAST(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "FIRST(\"DOWN\".\"net_weight\", 0), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "LAST(\"UP\".\"net_weight\", 0))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeDefineClause3() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\",1),\n"
                + "      up as up.\"net_weight\" > LAST(up.\"net_weight\" + up.\"gross_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "LAST(\"UP\".\"net_weight\", 0) + LAST(\"UP\".\"gross_weight\", 0))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeDefineClause4() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\",1),\n"
                + "      up as up.\"net_weight\" > "
                + "PREV(LAST(up.\"net_weight\" + up.\"gross_weight\"),3)\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(LAST(\"UP\".\"net_weight\", 0) + "
                + "LAST(\"UP\".\"gross_weight\", 0), 3))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeMeasures1() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures MATCH_NUMBER() as match_num, "
                + "   CLASSIFIER() as var_match, "
                + "   STRT.\"net_weight\" as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   LAST(up.\"net_weight\") as end_nw"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"MATCH_NUM\", \"VAR_MATCH\", \"START_NW\", \"BOTTOM_NW\", \"END_NW\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL MATCH_NUMBER () AS \"MATCH_NUM\", "
                + "FINAL CLASSIFIER() AS \"VAR_MATCH\", "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"END_NW\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeMeasures2() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   FINAL LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   LAST(up.\"net_weight\") as end_nw"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"START_NW\", \"BOTTOM_NW\", \"END_NW\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"END_NW\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeMeasures3() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   RUNNING LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   LAST(up.\"net_weight\") as end_nw"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"START_NW\", \"BOTTOM_NW\", \"END_NW\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL (RUNNING LAST(\"DOWN\".\"net_weight\", 0)) AS \"BOTTOM_NW\", "
                + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"END_NW\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeMeasures4() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   FINAL COUNT(up.\"net_weight\") as up_cnt,"
                + "   FINAL COUNT(\"net_weight\") as down_cnt,"
                + "   RUNNING COUNT(\"net_weight\") as running_cnt"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"START_NW\", \"UP_CNT\", \"DOWN_CNT\", \"RUNNING_CNT\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL COUNT(\"UP\".\"net_weight\") AS \"UP_CNT\", "
                + "FINAL COUNT(\"*\".\"net_weight\") AS \"DOWN_CNT\", "
                + "FINAL (RUNNING COUNT(\"*\".\"net_weight\")) AS \"RUNNING_CNT\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    @Ignore("Unnecessary casts")
    public void testMatchRecognizeMeasures5() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures "
                + "   FIRST(STRT.\"net_weight\") as start_nw,"
                + "   LAST(UP.\"net_weight\") as up_cnt,"
                + "   AVG(DOWN.\"net_weight\") as down_cnt"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT *\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL FIRST(\"STRT\".\"net_weight\", 0) AS \"START_NW\", "
                + "FINAL LAST(\"UP\".\"net_weight\", 0) AS \"UP_CNT\", "
                + "FINAL (SUM(\"DOWN\".\"net_weight\") / "
                + "COUNT(\"DOWN\".\"net_weight\")) AS \"DOWN_CNT\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeMeasures6() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures "
                + "   FIRST(STRT.\"net_weight\") as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as up_cnt,"
                + "   FINAL SUM(DOWN.\"net_weight\") as down_cnt"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"START_NW\", \"UP_CNT\", \"DOWN_CNT\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL FIRST(\"STRT\".\"net_weight\", 0) AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"UP_CNT\", "
                + "FINAL SUM(\"DOWN\".\"net_weight\") AS \"DOWN_CNT\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN "
                + "(\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeMeasures7() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures "
                + "   FIRST(STRT.\"net_weight\") as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as up_cnt,"
                + "   FINAL SUM(DOWN.\"net_weight\") as down_cnt"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr order by start_nw, up_cnt";

        final String expected = "SELECT \"START_NW\", \"UP_CNT\", \"DOWN_CNT\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL FIRST(\"STRT\".\"net_weight\", 0) AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"UP_CNT\", "
                + "FINAL SUM(\"DOWN\".\"net_weight\") AS \"DOWN_CNT\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN "
                + "(\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))\n"
                + "ORDER BY \"START_NW\", \"UP_CNT\"";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternSkip1() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    after match skip to next row\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternSkip2() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    after match skip past last row\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP PAST LAST ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternSkip3() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    after match skip to FIRST down\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO FIRST \"DOWN\"\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE \"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternSkip4() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    after match skip to last down\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO LAST \"DOWN\"\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizePatternSkip5() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    after match skip to down\n"
                + "    pattern (strt down+ up+)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO LAST \"DOWN\"\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeSubset1() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "    after match skip to down\n"
                + "    pattern (strt down+ up+)\n"
                + "    subset stdn = (strt, down)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > NEXT(up.\"net_weight\")\n"
                + "  ) mr";
        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") MATCH_RECOGNIZE(\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO LAST \"DOWN\"\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\")\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "NEXT(PREV(\"UP\".\"net_weight\", 0), 1))";
        sql( sql ).ok( expected );
    }


    @Test
    @Ignore("Unnecessary casts")
    public void testMatchRecognizeSubset2() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   AVG(STDN.\"net_weight\") as avg_stdn"
                + "    pattern (strt down+ up+)\n"
                + "    subset stdn = (strt, down)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT *\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "FINAL (SUM(\"STDN\".\"net_weight\") / "
                + "COUNT(\"STDN\".\"net_weight\")) AS \"AVG_STDN\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\")\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeSubset3() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   SUM(STDN.\"net_weight\") as avg_stdn"
                + "    pattern (strt down+ up+)\n"
                + "    subset stdn = (strt, down)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"START_NW\", \"BOTTOM_NW\", \"AVG_STDN\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "FINAL SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\")\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeSubset4() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   SUM(STDN.\"net_weight\") as avg_stdn"
                + "    pattern (strt down+ up+)\n"
                + "    subset stdn = (strt, down), stdn2 = (strt, down)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"START_NW\", \"BOTTOM_NW\", \"AVG_STDN\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "FINAL SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\"), \"STDN2\" = (\"DOWN\", \"STRT\")\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeRowsPerMatch1() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   SUM(STDN.\"net_weight\") as avg_stdn"
                + "    ONE ROW PER MATCH\n"
                + "    pattern (strt down+ up+)\n"
                + "    subset stdn = (strt, down), stdn2 = (strt, down)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"START_NW\", \"BOTTOM_NW\", \"AVG_STDN\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "FINAL \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "FINAL LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "FINAL SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
                + "ONE ROW PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\"), \"STDN2\" = (\"DOWN\", \"STRT\")\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeRowsPerMatch2() {
        final String sql = "select *\n"
                + "  from \"product\" match_recognize\n"
                + "  (\n"
                + "   measures STRT.\"net_weight\" as start_nw,"
                + "   LAST(DOWN.\"net_weight\") as bottom_nw,"
                + "   SUM(STDN.\"net_weight\") as avg_stdn"
                + "    ALL ROWS PER MATCH\n"
                + "    pattern (strt down+ up+)\n"
                + "    subset stdn = (strt, down), stdn2 = (strt, down)\n"
                + "    define\n"
                + "      down as down.\"net_weight\" < PREV(down.\"net_weight\"),\n"
                + "      up as up.\"net_weight\" > prev(up.\"net_weight\")\n"
                + "  ) mr";

        final String expected = "SELECT \"product_class_id\", \"product_id\", \"brand_name\", \"product_name\", \"SKU\", \"SRP\", \"gross_weight\", \"net_weight\", \"recyclable_package\", \"low_fat\", \"units_per_case\", \"cases_per_pallet\", \"shelf_width\", \"shelf_height\", \"shelf_depth\", \"START_NW\", \"BOTTOM_NW\", \"AVG_STDN\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"product\") "
                + "MATCH_RECOGNIZE(\n"
                + "MEASURES "
                + "RUNNING \"STRT\".\"net_weight\" AS \"START_NW\", "
                + "RUNNING LAST(\"DOWN\".\"net_weight\", 0) AS \"BOTTOM_NW\", "
                + "RUNNING SUM(\"STDN\".\"net_weight\") AS \"AVG_STDN\"\n"
                + "ALL ROWS PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +)\n"
                + "SUBSET \"STDN\" = (\"DOWN\", \"STRT\"), \"STDN2\" = (\"DOWN\", \"STRT\")\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"net_weight\", 0) < "
                + "PREV(\"DOWN\".\"net_weight\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"net_weight\", 0) > "
                + "PREV(\"UP\".\"net_weight\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testMatchRecognizeWithin() {
        final String sql = "select *\n"
                + "  from \"employee\" match_recognize\n"
                + "  (\n"
                + "   order by \"hire_date\"\n"
                + "   ALL ROWS PER MATCH\n"
                + "   pattern (strt down+ up+) within interval '3:12:22.123' hour to second\n"
                + "   define\n"
                + "     down as down.\"salary\" < PREV(down.\"salary\"),\n"
                + "     up as up.\"salary\" > prev(up.\"salary\")\n"
                + "  ) mr";

        final String expected = "SELECT \"employee_id\", \"full_name\", \"first_name\", \"last_name\", \"position_id\", \"position_title\", \"store_id\", \"department_id\", \"birth_date\", \"hire_date\", \"end_date\", \"salary\", \"supervisor_id\", \"education_level\", \"marital_status\", \"gender\", \"management_role\"\n"
                + "FROM (SELECT *\n"
                + "FROM \"foodmart\".\"employee\") "
                + "MATCH_RECOGNIZE(\n"
                + "ORDER BY \"hire_date\"\n"
                + "ALL ROWS PER MATCH\n"
                + "AFTER MATCH SKIP TO NEXT ROW\n"
                + "PATTERN (\"STRT\" \"DOWN\" + \"UP\" +) WITHIN INTERVAL '3:12:22.123' HOUR TO SECOND\n"
                + "DEFINE "
                + "\"DOWN\" AS PREV(\"DOWN\".\"salary\", 0) < "
                + "PREV(\"DOWN\".\"salary\", 1), "
                + "\"UP\" AS PREV(\"UP\".\"salary\", 0) > "
                + "PREV(\"UP\".\"salary\", 1))";
        sql( sql ).ok( expected );
    }


    @Test
    public void testValues() {
        final String sql = "select \"a\"\n"
                + "from (values (1, 'x'), (2, 'yy')) as t(\"a\", \"b\")";
        final String expectedHsqldb = "SELECT \"a\"\n"
                + "FROM (VALUES  (1, 'x '),\n"
                + " (2, 'yy')) AS \"t\" (\"a\", \"b\")";
        final String expectedPostgresql = "SELECT \"a\"\n"
                + "FROM (VALUES  (1, 'x '),\n"
                + " (2, 'yy')) AS \"t\" (\"a\", \"b\")";
        final String expectedOracle = "SELECT \"a\"\n"
                + "FROM (SELECT 1 \"a\", 'x ' \"b\"\n"
                + "FROM \"DUAL\"\n"
                + "UNION ALL\n"
                + "SELECT 2 \"a\", 'yy' \"b\"\n"
                + "FROM \"DUAL\")";
        sql( sql )
                .withHsqldb()
                .ok( expectedHsqldb )
                .withPostgresql()
                .ok( expectedPostgresql )
                .withOracle()
                .ok( expectedOracle );
    }


    /**
     * Test case for "AlgToSqlConverter should only generate "*" if field names match".
     */
    @Test
    public void testPreserveAlias() {
        final String sql = "select \"warehouse_class_id\" as \"id\",\n"
                + " \"description\"\n"
                + "from \"warehouse_class\"";
        final String expected = ""
                + "SELECT \"warehouse_class_id\" AS \"id\", \"description\"\n"
                + "FROM \"foodmart\".\"warehouse_class\"";
        sql( sql ).ok( expected );

        final String sql2 = "select \"warehouse_class_id\", \"description\"\n"
                + "from \"warehouse_class\"";
        final String expected2 = "SELECT \"warehouse_class_id\", \"description\"\n"
                + "FROM \"foodmart\".\"warehouse_class\"";
        sql( sql2 ).ok( expected2 );
    }


    @Test
    public void testPreservePermutation() {
        final String sql = "select \"description\", \"warehouse_class_id\"\n"
                + "from \"warehouse_class\"";
        final String expected = "SELECT \"description\", \"warehouse_class_id\"\n"
                + "FROM \"foodmart\".\"warehouse_class\"";
        sql( sql ).ok( expected );
    }


    @Test
    public void testFieldNamesWithAggregateSubQuery() {
        final String query = "select mytable.\"city\",\n"
                + "  sum(mytable.\"store_sales\") as \"my-alias\"\n"
                + "from (select c.\"city\", s.\"store_sales\"\n"
                + "  from \"sales_fact_1997\" as s\n"
                + "    join \"customer\" as c using (\"customer_id\")\n"
                + "  group by c.\"city\", s.\"store_sales\") AS mytable\n"
                + "group by mytable.\"city\"";

        final String expected = "SELECT \"t0\".\"city\", SUM(\"t0\".\"store_sales\") AS \"my-alias\"\n"
                + "FROM (SELECT \"customer\".\"city\"," + " \"sales_fact_1997\".\"store_sales\"\n"
                + "FROM \"foodmart\".\"sales_fact_1997\"\n"
                + "INNER JOIN \"foodmart\".\"customer\" ON \"sales_fact_1997\".\"customer_id\" = \"customer\".\"customer_id\"\n"
                + "GROUP BY \"customer\".\"city\", \"sales_fact_1997\".\"store_sales\") AS \"t0\"\n"
                + "GROUP BY \"t0\".\"city\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testUnparseSelectMustUseDialect() {
        final String query = "select * from \"product\"";
        final String expected = "SELECT product_class_id, product_id, brand_name, product_name, SKU, SRP, gross_weight, net_weight, recyclable_package, low_fat, units_per_case, cases_per_pallet, shelf_width, shelf_height, shelf_depth\n"
                + "FROM foodmart.product";

        final boolean[] callsUnparseCallOnSqlSelect = { false };
        final SqlDialect dialect = new SqlDialect( SqlDialect.EMPTY_CONTEXT ) {
            @Override
            public void unparseCall( SqlWriter writer, SqlCall call, int leftPrec, int rightPrec ) {
                if ( call instanceof SqlSelect ) {
                    callsUnparseCallOnSqlSelect[0] = true;
                }
                super.unparseCall( writer, call, leftPrec, rightPrec );
            }
        };
        sql( query ).dialect( dialect ).ok( expected );

        assertThat( "Dialect must be able to customize unparseCall() for SqlSelect", callsUnparseCallOnSqlSelect[0], is( true ) );
    }


    @Test
    public void testWithinGroup1() {
        final String query = "select \"product_class_id\", collect(\"net_weight\") within group (order by \"net_weight\" desc) from \"product\" group by \"product_class_id\"";
        final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
                + "WITHIN GROUP (ORDER BY \"net_weight\" DESC)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testWithinGroup2() {
        final String query = "select \"product_class_id\", collect(\"net_weight\") within group (order by \"low_fat\", \"net_weight\" desc nulls last) from \"product\" group by \"product_class_id\"";
        final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") WITHIN GROUP (ORDER BY \"low_fat\", \"net_weight\" DESC NULLS LAST)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testWithinGroup3() {
        final String query = "select \"product_class_id\", collect(\"net_weight\") "
                + "within group (order by \"net_weight\" desc), min(\"low_fat\")"
                + "from \"product\" group by \"product_class_id\"";
        final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
                + "WITHIN GROUP (ORDER BY \"net_weight\" DESC), MIN(\"low_fat\")\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testWithinGroup4() {
        // filter in AggregateCall is not unparsed
        final String query = "select \"product_class_id\", collect(\"net_weight\") "
                + "within group (order by \"net_weight\" desc) filter (where \"net_weight\" > 0)"
                + "from \"product\" group by \"product_class_id\"";
        final String expected = "SELECT \"product_class_id\", COLLECT(\"net_weight\") "
                + "WITHIN GROUP (ORDER BY \"net_weight\" DESC)\n"
                + "FROM \"foodmart\".\"product\"\n"
                + "GROUP BY \"product_class_id\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonExists() {
        String query = "select json_exists(\"product_name\", 'lax $') from \"product\"";
        final String expected = "SELECT JSON_EXISTS(\"product_name\" FORMAT JSON, 'lax $')\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonValue() {
        String query = "select json_value(\"product_name\", 'lax $') from \"product\"";
        // TODO: translate to JSON_VALUE rather than CAST
        final String expected = "SELECT CAST(JSON_VALUE_ANY(\"product_name\" FORMAT JSON, "
                + "'lax $' NULL ON EMPTY NULL ON ERROR) AS VARCHAR(2000))\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonQuery() {
        String query = "select json_query(\"product_name\", 'lax $') from \"product\"";
        final String expected = "SELECT JSON_QUERY(\"product_name\" FORMAT JSON, 'lax $' "
                + "WITHOUT ARRAY WRAPPER NULL ON EMPTY NULL ON ERROR)\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonArray() {
        String query = "select json_array(\"product_name\", \"product_name\") from \"product\"";
        final String expected = "SELECT JSON_ARRAY(\"product_name\", \"product_name\" ABSENT ON NULL)\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonArrayAgg() {
        String query = "select json_arrayagg(\"product_name\") from \"product\"";
        final String expected = "SELECT JSON_ARRAYAGG(\"product_name\" ABSENT ON NULL)\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    @Ignore
    public void testJsonObject() {
        String query = "select json_object(\"product_name\": \"product_id\") from \"product\"";
        final String expected = "SELECT "
                + "JSON_OBJECT(KEY \"product_name\" VALUE \"product_id\" NULL ON NULL)\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonObjectAgg() {
        String query = "select json_objectagg(\"product_name\": \"product_id\") from \"product\"";
        final String expected = "SELECT JSON_OBJECTAGG(KEY \"product_name\" VALUE \"product_id\" NULL ON NULL)\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    @Test
    public void testJsonPredicate() {
        String query = "select "
                + "\"product_name\" is json, "
                + "\"product_name\" is json value, "
                + "\"product_name\" is json object, "
                + "\"product_name\" is json array, "
                + "\"product_name\" is json scalar, "
                + "\"product_name\" is not json, "
                + "\"product_name\" is not json value, "
                + "\"product_name\" is not json object, "
                + "\"product_name\" is not json array, "
                + "\"product_name\" is not json scalar "
                + "from \"product\"";
        final String expected = "SELECT "
                + "\"product_name\" IS JSON VALUE, "
                + "\"product_name\" IS JSON VALUE, "
                + "\"product_name\" IS JSON OBJECT, "
                + "\"product_name\" IS JSON ARRAY, "
                + "\"product_name\" IS JSON SCALAR, "
                + "\"product_name\" IS NOT JSON VALUE, "
                + "\"product_name\" IS NOT JSON VALUE, "
                + "\"product_name\" IS NOT JSON OBJECT, "
                + "\"product_name\" IS NOT JSON ARRAY, "
                + "\"product_name\" IS NOT JSON SCALAR\n"
                + "FROM \"foodmart\".\"product\"";
        sql( query ).ok( expected );
    }


    /**
     * Fluid interface to run tests.
     */
    static class Sql {

        private final SchemaPlus schema;
        private final String sql;
        private final SqlDialect dialect;
        private final List<Function<AlgNode, AlgNode>> transforms;
        private final Config config;


        Sql( SchemaPlus schema, String sql, SqlDialect dialect, Config config, List<Function<AlgNode, AlgNode>> transforms ) {
            this.schema = schema;
            this.sql = sql;
            this.dialect = dialect;
            this.transforms = ImmutableList.copyOf( transforms );
            this.config = config;
        }


        Sql dialect( SqlDialect dialect ) {
            return new Sql( schema, sql, dialect, config, transforms );
        }


        Sql withDb2() {
            return dialect( DatabaseProduct.DB2.getDialect() );
        }


        Sql withHive() {
            return dialect( DatabaseProduct.HIVE.getDialect() );
        }


        Sql withHsqldb() {
            return dialect( DatabaseProduct.HSQLDB.getDialect() );
        }


        Sql withMssql() {
            return dialect( DatabaseProduct.MSSQL.getDialect() );
        }


        Sql withMysql() {
            return dialect( DatabaseProduct.MYSQL.getDialect() );
        }


        Sql withOracle() {
            return dialect( DatabaseProduct.ORACLE.getDialect() );
        }


        Sql withPostgresql() {
            return dialect( DatabaseProduct.POSTGRESQL.getDialect() );
        }


        Sql withVertica() {
            return dialect( DatabaseProduct.VERTICA.getDialect() );
        }


        Sql withBigquery() {
            return dialect( DatabaseProduct.BIG_QUERY.getDialect() );
        }


        Sql withPostgresqlModifiedTypeSystem() {
            // Postgresql dialect with max length for varchar set to 256
            final PostgresqlSqlDialect postgresqlSqlDialect =
                    new PostgresqlSqlDialect( SqlDialect.EMPTY_CONTEXT
                            .withDatabaseProduct( DatabaseProduct.POSTGRESQL )
                            .withIdentifierQuoteString( "\"" )
                            .withDataTypeSystem( new AlgDataTypeSystemImpl() {
                                @Override
                                public int getMaxPrecision( PolyType typeName ) {
                                    switch ( typeName ) {
                                        case VARCHAR:
                                            return 256;
                                        default:
                                            return super.getMaxPrecision( typeName );
                                    }
                                }
                            } ) );
            return dialect( postgresqlSqlDialect );
        }


        Sql config( Config config ) {
            return new Sql( schema, sql, dialect, config, transforms );
        }


        Sql optimize( final RuleSet ruleSet, final AlgOptPlanner algOptPlanner ) {
            return new Sql( schema, sql, dialect, config,
                    FlatLists.append( transforms, r -> {
                        Program program = Programs.of( ruleSet );
                        return program.run( algOptPlanner, r, r.getTraitSet() );
                    } ) );
        }


        Sql ok( String expectedQuery ) {
            assertThat( exec(), Matchers.isLinux( expectedQuery ) );
            return this;
        }


        Sql throws_( String errorMessage ) {
            try {
                final String s = exec();
                throw new AssertionError( "Expected exception with message `" + errorMessage + "` but nothing was thrown; got " + s );
            } catch ( Exception e ) {
                assertThat( e.getMessage(), is( errorMessage ) );
                return this;
            }
        }


        String exec() {
            final Planner planner = getPlanner( null, Parser.ParserConfig.DEFAULT, schema, config );
            try {
                Node parse = planner.parse( sql );
                Node validate = planner.validate( parse );
                AlgNode alg = planner.alg( validate ).alg;
                for ( Function<AlgNode, AlgNode> transform : transforms ) {
                    alg = transform.apply( alg );
                }
                return toSql( alg, dialect );
            } catch ( RuntimeException e ) {
                throw e;
            } catch ( Exception e ) {
                throw new RuntimeException( e );
            }
        }

    }

}

