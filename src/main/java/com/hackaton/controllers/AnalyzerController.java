package com.hackaton.controllers;

import com.hackaton.dao.ColumnDaoService;
import com.hackaton.SchemaCompareService;
import com.hackaton.dao.JDBCService;
import com.hackaton.dao.RowDaoService;
import com.hackaton.dao.TableSchema;
import com.hackaton.data.ConnectionConfig;
import com.hackaton.data.JSONReader;
import com.hackaton.data.Tables;
import com.hackaton.response.AnalysisResponseRoot;
import com.hackaton.response.OperationStatus;
import com.hackaton.response.TableSchemaAnalysisResult;
import io.atlassian.fugue.Either;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RestController
public class AnalyzerController {

    private final JSONReader<Tables> jsonReader;

    @Autowired
    private ColumnDaoService columnDaoService;

    @Autowired
    private RowDaoService rowDaoService;

    @Autowired
    private SchemaCompareService schemaCompareService;

    @Autowired
    private JDBCService dataSourceWrapper;


    public AnalyzerController() {
        this.jsonReader = new JSONReader<>(Tables.class);
    }

    @CrossOrigin(origins = "*")
    @RequestMapping(value = "/api/v1/connectToDB", method = RequestMethod.POST, consumes = "application/json", produces = "application/json")
    public AnalysisResponseRoot connectToDatabase(@RequestBody String body) {
        try {
            return connectToDB(body);
        } catch (Exception e) {
            log.error("Can't process request to connect to db + " + body + " . Exception occurred.", e);
            return AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, e.getMessage());
        }
    }

    public AnalysisResponseRoot connectToDB(String body) throws Exception {
        log.info("Request to connect to db {}.", body);
        JSONReader<ConnectionConfig> jsonReader = new JSONReader<>(ConnectionConfig.class);
        ConnectionConfig connectionConfig = jsonReader.readJSON(body);
        dataSourceWrapper.updateConnection(connectionConfig);
        if (rowDaoService.testConnection()) {
            return AnalysisResponseRoot.success();
        }
        return AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, "Can't connect using provided connection config");
    }

    @CrossOrigin(origins = "*")
    @RequestMapping(value = "/api/v1/getTables", method = RequestMethod.GET, produces = "application/json")
    public AnalysisResponseRoot getTables(@RequestParam(value = "schema") String schema) {
        try {
            return getTablesForSchema(schema);
        } catch (Exception e) {
            log.error("Can't process request to get table names for schema" + schema + " . Exception occurred.", e);
            return AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, e.getMessage());
        }
    }

    @CrossOrigin(origins = "*")
    @RequestMapping(value = "/api/v1/gatherDataForAnalysis", method = RequestMethod.POST, consumes = "application/json", produces = "application/json")
    public AnalysisResponseRoot gatherDataForAnalysis(@RequestBody String body) {
        try {
            return gatherTablesSchemas(body);
        } catch (Exception e) {
            log.error("Can't process request to gather tables + " + body + " schemas and data. Exception occurred.", e);
            return AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, e.getMessage());
        }
    }

    @CrossOrigin(origins = "*")
    @RequestMapping(value = "/api/v1/analyze", method = RequestMethod.POST, consumes = "application/json", produces = "application/json")
    public AnalysisResponseRoot performAnalysis(@RequestParam(value = "analysisId") String analysisId) {
        try {
            return performTableSchemaAnalysis(analysisId);
        } catch (Exception e) {
            log.error("Can't perform table schema analysis for data gathered for analysisId {}", analysisId, e);
            return AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, e.getMessage());
        }
    }

    private AnalysisResponseRoot getTablesForSchema(String schemaName) {
        log.info("Request to get table names for schema {}.", schemaName);
        Optional<List<String>> tableNameOptional = columnDaoService.streamTables(schemaName);
        if (!tableNameOptional.isPresent()) {
            log.warn("Failed to table names for schema: {}", schemaName);
            return AnalysisResponseRoot.error(OperationStatus.DATA_NOT_FOUND, "Failed to table names for schema: " + schemaName);
        }
        List<String> tableNames = tableNameOptional.get();
        log.info("schema {}");
        return AnalysisResponseRoot.success(null, tableNames);
    }

    private AnalysisResponseRoot performTableSchemaAnalysis(String oldAnalysisId) {
        log.info("Request to perform table schema analysis for data gathered for analysisId {}.", oldAnalysisId);
        String newAnalysisId = schemaCompareService.generateRandomID();
        List<TableSchema> oldTableSchemas = schemaCompareService.getSchemasForAnalysis(oldAnalysisId);

        if (oldTableSchemas.isEmpty()) {
            log.error("Failed to fetch schema for analysisId: {}", oldAnalysisId);
            return AnalysisResponseRoot.error(OperationStatus.DATA_NOT_FOUND, "Failed to fetch data for analysisId: " + oldAnalysisId);
        }

        List<String> tableNames = oldTableSchemas.stream().map(TableSchema::getTableName).collect(Collectors.toList());

        Either<AnalysisResponseRoot, List<TableSchema>> result = gatherSchema(tableNames);
        if (result.isLeft()) {
            return result.left().get();
        }
        List<TableSchema> newTableSchemas = result.right().get();
        schemaCompareService.saveSchemas(newAnalysisId, newTableSchemas);
        Map<String, TableSchema> newTableSchemaMap = newTableSchemas.stream().collect(Collectors.toMap(TableSchema::getTableName, s -> s ));

        List<TableSchemaAnalysisResult> analysisResults = new ArrayList<>();

        for (TableSchema oldTableSchema : oldTableSchemas) {
            TableSchema newTableSchema = newTableSchemaMap.get(oldTableSchema.getTableName());
            TableSchemaAnalysisResult schemaAnalysisResult = schemaCompareService.performSchemaAnalysis(oldTableSchema, newTableSchema);
            log.info("Analysis result for table {}: {}", oldTableSchema.getTableName(), schemaAnalysisResult);
            analysisResults.add(schemaAnalysisResult);
        }

        return AnalysisResponseRoot.success(newAnalysisId, analysisResults);
    }

    private AnalysisResponseRoot gatherTablesSchemas(String body) throws Exception {
        Tables tables = jsonReader.readJSON(body);
        List<String> tableNames = tables.getValues();
        log.info("Got request to gather tables {} schemas.", tableNames);
        String analysisId = schemaCompareService.generateRandomID();

        Either<AnalysisResponseRoot, List<TableSchema>> result = gatherSchema(tableNames);
        if (result.isLeft()) {
            return result.left().get();
        }
        List<TableSchema> tableSchemas = result.right().get();
        schemaCompareService.saveSchemas(analysisId, tableSchemas);

        return AnalysisResponseRoot.success(analysisId);
    }

    private Either<AnalysisResponseRoot, List<TableSchema>> gatherSchema(List<String> tableNames) {
        List<TableSchema> tableSchemas = new ArrayList<>(tableNames.size());

        for(String tableName: tableNames) {

            int tableVersion = schemaCompareService.getAndIncCurrentTableVersion(tableName);
            Optional<TableSchema> tableSchemaOptional = columnDaoService.streamColumns(tableVersion, tableName);

            if (!tableSchemaOptional.isPresent()) {
                log.error("Failed to fetch schema for table: {}", tableName);
                return Either.left(AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, "Failed to fetch schema for table: " + tableName));
            }

            TableSchema schema = tableSchemaOptional.get();
            Optional<TableSchema> tableSchemaOptionalRows = rowDaoService.streamRows(tableName, schema);

            if (!tableSchemaOptionalRows.isPresent()) {
                log.error("Failed to fetch rows for table: {}", tableName);
                return Either.left(AnalysisResponseRoot.error(OperationStatus.INTERNAL_ERROR, "Failed to fetch rows for table: " + tableName));
            }

            log.info("table {} schema: {}", tableName, schema);
            log.info("table {} schema rows: {}", tableName, tableSchemaOptionalRows.get());
            tableSchemas.add(schema);
        }
        return Either.right(tableSchemas);
    }

}
