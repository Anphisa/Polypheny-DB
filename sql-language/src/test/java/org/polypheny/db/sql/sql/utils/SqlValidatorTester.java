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

package org.polypheny.db.sql.sql.utils;


import org.polypheny.db.sql.sql.SqlTestFactory;
import org.polypheny.db.sql.sql.validate.SqlValidator;


/**
 * Tester of {@link SqlValidator}.
 */
public class SqlValidatorTester extends AbstractSqlTester {

    public SqlValidatorTester( SqlTestFactory factory ) {
        super( factory );
    }


    @Override
    protected SqlTester with( SqlTestFactory factory ) {
        return new SqlValidatorTester( factory );
    }

}

