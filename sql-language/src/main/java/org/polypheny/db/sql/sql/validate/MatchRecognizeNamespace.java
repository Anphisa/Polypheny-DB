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

package org.polypheny.db.sql.sql.validate;


import org.polypheny.db.algebra.type.AlgDataType;
import org.polypheny.db.sql.sql.SqlMatchRecognize;
import org.polypheny.db.sql.sql.SqlNode;


/**
 * Namespace for a {@code MATCH_RECOGNIZE} clause.
 */
public class MatchRecognizeNamespace extends AbstractNamespace {

    private final SqlMatchRecognize matchRecognize;


    /**
     * Creates a MatchRecognizeNamespace.
     */
    protected MatchRecognizeNamespace( SqlValidatorImpl validator, SqlMatchRecognize matchRecognize, SqlNode enclosingNode ) {
        super( validator, enclosingNode );
        this.matchRecognize = matchRecognize;
    }


    @Override
    public AlgDataType validateImpl( AlgDataType targetRowType ) {
        validator.validateMatchRecognize( matchRecognize );
        return rowType;
    }


    @Override
    public SqlMatchRecognize getNode() {
        return matchRecognize;
    }

}

