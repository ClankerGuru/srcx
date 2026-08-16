package zone.clanker.docx.index.database

internal val workspaceIndexSchema: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS workspace_generations (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            source_workspace_id TEXT NOT NULL,
            workspace_name TEXT NOT NULL,
            state TEXT NOT NULL CHECK (state IN ('STAGING', 'READY')),
            expected_project_count INTEGER NOT NULL,
            imported_project_count INTEGER NOT NULL DEFAULT 0,
            build_count INTEGER NOT NULL DEFAULT 0,
            source_set_count INTEGER NOT NULL DEFAULT 0,
            file_count INTEGER NOT NULL DEFAULT 0,
            symbol_count INTEGER NOT NULL DEFAULT 0,
            relationship_count INTEGER NOT NULL DEFAULT 0,
            finding_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (workspace_id, generation_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS active_workspace_generations (
            workspace_id TEXT PRIMARY KEY,
            generation_id TEXT NOT NULL,
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS builds (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            build_id TEXT NOT NULL,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, build_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS projects (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            build_id TEXT NOT NULL,
            path TEXT NOT NULL,
            build_file TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, project_id),
            FOREIGN KEY (workspace_id, generation_id, build_id)
                REFERENCES builds (workspace_id, generation_id, build_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS source_sets (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            source_set_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            name TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, source_set_id),
            FOREIGN KEY (workspace_id, generation_id, project_id)
                REFERENCES projects (workspace_id, generation_id, project_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS source_files (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            file_id TEXT NOT NULL,
            build_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            source_set_id TEXT NOT NULL,
            project_relative_path TEXT NOT NULL,
            language TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, file_id),
            FOREIGN KEY (workspace_id, generation_id, source_set_id)
                REFERENCES source_sets (workspace_id, generation_id, source_set_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS symbols (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            symbol_id TEXT NOT NULL,
            build_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            source_set_id TEXT NOT NULL,
            source_set_name TEXT NOT NULL,
            file_id TEXT NOT NULL,
            file_path TEXT NOT NULL,
            name TEXT NOT NULL,
            qualified_name TEXT NOT NULL,
            package_name TEXT NOT NULL,
            kind TEXT NOT NULL,
            declaration_semantic TEXT NOT NULL,
            declaration_line INTEGER NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, symbol_id),
            FOREIGN KEY (workspace_id, generation_id, file_id)
                REFERENCES source_files (workspace_id, generation_id, file_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS symbol_evidence (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            symbol_id TEXT NOT NULL,
            owner_symbol_id TEXT,
            signature TEXT,
            declaration_start_offset INTEGER,
            declaration_end_offset_exclusive INTEGER,
            PRIMARY KEY (workspace_id, generation_id, symbol_id),
            FOREIGN KEY (workspace_id, generation_id, symbol_id)
                REFERENCES symbols (workspace_id, generation_id, symbol_id) ON DELETE CASCADE,
            CHECK (
                (declaration_start_offset IS NULL AND declaration_end_offset_exclusive IS NULL) OR
                (declaration_start_offset >= 0 AND declaration_end_offset_exclusive > declaration_start_offset)
            )
        )
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS symbol_fts USING fts5(
            workspace_id UNINDEXED,
            generation_id UNINDEXED,
            symbol_id UNINDEXED,
            name,
            qualified_name,
            package_name,
            file_path,
            tokenize = 'unicode61 remove_diacritics 2'
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS relationships (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            relationship_id TEXT NOT NULL,
            build_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            source_set_id TEXT NOT NULL,
            source_file_id TEXT NOT NULL,
            reference_id TEXT NOT NULL,
            source_symbol_id TEXT,
            target_symbol_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            evidence TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, relationship_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS relationship_occurrences (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            relationship_id TEXT NOT NULL,
            reference_id TEXT NOT NULL,
            source_file_id TEXT NOT NULL,
            source_file_path TEXT NOT NULL,
            source_line INTEGER NOT NULL,
            source_context TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, relationship_id),
            FOREIGN KEY (workspace_id, generation_id, relationship_id)
                REFERENCES relationships (workspace_id, generation_id, relationship_id) ON DELETE CASCADE,
            CHECK (source_line > 0)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS relationship_occurrence_ranges (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            relationship_id TEXT NOT NULL,
            start_offset INTEGER NOT NULL,
            end_offset_exclusive INTEGER NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, relationship_id),
            FOREIGN KEY (workspace_id, generation_id, relationship_id)
                REFERENCES relationship_occurrences (
                    workspace_id, generation_id, relationship_id
                ) ON DELETE CASCADE,
            CHECK (start_offset >= 0 AND end_offset_exclusive > start_offset)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS findings (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            finding_id TEXT NOT NULL,
            build_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            source_set_id TEXT,
            file_id TEXT,
            file_path TEXT,
            line INTEGER,
            severity TEXT NOT NULL,
            message TEXT NOT NULL,
            suggestion TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, project_id, finding_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS build_edges (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            edge_id TEXT NOT NULL,
            source_build_id TEXT NOT NULL,
            target_build_id TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, edge_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_finding_evidence (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            finding_id TEXT NOT NULL,
            file_id TEXT,
            severity TEXT NOT NULL,
            message TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, project_id, finding_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_finding_symbols (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            finding_id TEXT NOT NULL,
            symbol_id TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, project_id, finding_id, symbol_id),
            FOREIGN KEY (workspace_id, generation_id, project_id, finding_id)
                REFERENCES graph_finding_evidence (
                    workspace_id, generation_id, project_id, finding_id
                ) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_cycles (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            cycle_id TEXT NOT NULL,
            project_id TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, cycle_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_cycle_symbols (
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            cycle_id TEXT NOT NULL,
            position INTEGER NOT NULL,
            symbol_id TEXT NOT NULL,
            PRIMARY KEY (workspace_id, generation_id, cycle_id, position),
            FOREIGN KEY (workspace_id, generation_id, cycle_id)
                REFERENCES graph_cycles (workspace_id, generation_id, cycle_id) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_nodes (
            node_key INTEGER PRIMARY KEY,
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            semantic_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            parent_node_key INTEGER,
            depth INTEGER NOT NULL,
            semantic_level INTEGER NOT NULL,
            direct_child_count INTEGER NOT NULL,
            descendant_count INTEGER NOT NULL,
            label TEXT NOT NULL,
            secondary_label TEXT,
            UNIQUE (workspace_id, generation_id, semantic_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE,
            FOREIGN KEY (parent_node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_node_closure (
            ancestor_node_key INTEGER NOT NULL,
            descendant_node_key INTEGER NOT NULL,
            distance INTEGER NOT NULL,
            PRIMARY KEY (ancestor_node_key, descendant_node_key),
            FOREIGN KEY (ancestor_node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE,
            FOREIGN KEY (descendant_node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_node_search (
            node_key INTEGER NOT NULL,
            target TEXT NOT NULL,
            normalized_value TEXT NOT NULL,
            PRIMARY KEY (node_key, target, normalized_value),
            FOREIGN KEY (node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_node_declarations (
            node_key INTEGER NOT NULL,
            declaration_kind TEXT NOT NULL,
            PRIMARY KEY (node_key, declaration_kind),
            FOREIGN KEY (node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_relation_facts (
            fact_key INTEGER PRIMARY KEY,
            workspace_id TEXT NOT NULL,
            generation_id TEXT NOT NULL,
            fact_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            source_node_key INTEGER NOT NULL,
            target_node_key INTEGER NOT NULL,
            UNIQUE (workspace_id, generation_id, fact_id),
            FOREIGN KEY (workspace_id, generation_id)
                REFERENCES workspace_generations (workspace_id, generation_id) ON DELETE CASCADE,
            FOREIGN KEY (source_node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE,
            FOREIGN KEY (target_node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS graph_relation_degrees (
            node_key INTEGER NOT NULL,
            relation_kind TEXT NOT NULL,
            outgoing_count INTEGER NOT NULL,
            incoming_count INTEGER NOT NULL,
            any_count INTEGER NOT NULL,
            PRIMARY KEY (node_key, relation_kind),
            FOREIGN KEY (node_key) REFERENCES graph_nodes (node_key) ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_projects_scope
            ON projects (workspace_id, generation_id, build_id, path)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_source_sets_scope
            ON source_sets (workspace_id, generation_id, project_id, name)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_files_scope
            ON source_files (workspace_id, generation_id, build_id, project_id, source_set_id)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_symbols_scope_kind
            ON symbols (workspace_id, generation_id, build_id, project_id, source_set_id, kind)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_symbols_qualified_name
            ON symbols (workspace_id, generation_id, qualified_name)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_symbol_evidence_owner
            ON symbol_evidence (workspace_id, generation_id, owner_symbol_id, symbol_id)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_relationships_scope_kind
            ON relationships (workspace_id, generation_id, build_id, project_id, source_set_id, kind)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_relationships_target
            ON relationships (workspace_id, generation_id, target_symbol_id)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_relationships_target_kind_id
            ON relationships (workspace_id, generation_id, target_symbol_id, kind, relationship_id)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_relationship_occurrences_source_order
            ON relationship_occurrences (
                workspace_id, generation_id, source_file_path, source_line, relationship_id
            )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_relationship_occurrence_ranges_start
            ON relationship_occurrence_ranges (workspace_id, generation_id, relationship_id, start_offset)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_findings_scope_severity
            ON findings (workspace_id, generation_id, build_id, project_id, source_set_id, severity)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_nodes_parent
            ON graph_nodes (workspace_id, generation_id, parent_node_key, semantic_id)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_nodes_kind
            ON graph_nodes (workspace_id, generation_id, kind, semantic_id)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_closure_descendant
            ON graph_node_closure (descendant_node_key, distance, ancestor_node_key)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_search_target_value
            ON graph_node_search (target, normalized_value, node_key)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_relations_source
            ON graph_relation_facts (workspace_id, generation_id, kind, source_node_key, fact_key)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_relations_target
            ON graph_relation_facts (workspace_id, generation_id, kind, target_node_key, fact_key)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_graph_degrees_count
            ON graph_relation_degrees (node_key, relation_kind, any_count, outgoing_count, incoming_count)
        """.trimIndent(),
    )
