/* SpagoBI, the Open Source Business Intelligence suite

 * Copyright (C) 2012 Engineering Ingegneria Informatica S.p.A. - SpagoBI Competency Center
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0, without the "Incompatible With Secondary Licenses" notice. 
 * If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package it.eng.qbe.statement;

import it.eng.qbe.datasource.AbstractDataSource;
import it.eng.qbe.datasource.ConnectionDescriptor;
import it.eng.qbe.model.structure.IModelField;
import it.eng.qbe.query.CalculatedSelectField;
import it.eng.qbe.query.HavingField;
import it.eng.qbe.query.ISelectField;
import it.eng.qbe.query.InLineCalculatedSelectField;
import it.eng.qbe.query.Query;
import it.eng.qbe.query.SimpleSelectField;
import it.eng.qbe.query.WhereField;
import it.eng.qbe.query.serializer.json.QueryJSONSerializer;
import it.eng.qbe.query.serializer.json.QuerySerializationConstants;
import it.eng.qbe.statement.hibernate.HQLStatement;
import it.eng.qbe.statement.hibernate.HQLStatement.IConditionalOperator;
import it.eng.spagobi.services.common.SsoServiceInterface;
import it.eng.spagobi.tools.dataset.bo.AbstractDataSet;
import it.eng.spagobi.tools.dataset.bo.DataSetVariable;
import it.eng.spagobi.tools.dataset.bo.JDBCDataSet;
import it.eng.spagobi.tools.dataset.common.datastore.DataStore;
import it.eng.spagobi.tools.dataset.common.datastore.Field;
import it.eng.spagobi.tools.dataset.common.datastore.IDataStore;
import it.eng.spagobi.tools.dataset.common.datastore.IDataStoreFilter;
import it.eng.spagobi.tools.dataset.common.datastore.IRecord;
import it.eng.spagobi.tools.dataset.common.datastore.Record;
import it.eng.spagobi.tools.dataset.common.metadata.FieldMetadata;
import it.eng.spagobi.tools.dataset.common.metadata.IFieldMetaData;
import it.eng.spagobi.tools.dataset.common.metadata.IFieldMetaData.FieldType;
import it.eng.spagobi.tools.dataset.common.metadata.IMetaData;
import it.eng.spagobi.tools.dataset.common.metadata.MetaData;
import it.eng.spagobi.tools.dataset.persist.IDataSetTableDescriptor;
import it.eng.spagobi.tools.datasource.bo.DataSource;
import it.eng.spagobi.tools.datasource.bo.IDataSource;
import it.eng.spagobi.utilities.StringUtils;
import it.eng.spagobi.utilities.assertion.Assert;
import it.eng.spagobi.utilities.engines.SpagoBIEngineRuntimeException;
import it.eng.spagobi.utilities.exceptions.SpagoBIRuntimeException;
import it.eng.spagobi.utilities.temporarytable.TemporaryTableManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.log4j.Logger;

public abstract class AbstractQbeDataSet extends AbstractDataSet {

	private IDataSource dataSource;
	protected IStatement statement;
	protected IDataStore dataStore;
	protected boolean abortOnOverflow;	
	protected Map bindings;
	protected Map userProfileAttributes;
	private boolean calculateResultNumberOnLoad = true;

	/** Logger component. */
	public static transient Logger logger = Logger.getLogger(AbstractQbeDataSet.class);

	public static final String PROPERTY_IS_SEGMENT_ATTRIBUTE = "isSegmentAttribute";
	public static final String PROPERTY_IS_MANDATORY_MEASURE = "isMandatoryMeasure";

	public AbstractQbeDataSet(IStatement statement) {
		setStatement(statement);
		bindings = new HashMap();
	}

	public IDataStore getDataStore() {
		return dataStore;
	}

	protected MetaData getDataStoreMeta(Query query) {
		MetaData dataStoreMeta;
		ISelectField queryFiled;
		FieldMetadata dataStoreFieldMeta;

		Map<String, String> aliasSelectedFields = QueryJSONSerializer.getFieldsNature(query, (AbstractDataSource)statement.getDataSource());

		dataStoreMeta = new MetaData();

		Iterator fieldsIterator = query.getSelectFields(true).iterator();
		while(fieldsIterator.hasNext()) {
			queryFiled = (ISelectField)fieldsIterator.next();

			dataStoreFieldMeta = new FieldMetadata();
			dataStoreFieldMeta.setAlias( queryFiled.getAlias() );
			if(queryFiled.isSimpleField()) {
				SimpleSelectField dataMartSelectField = (SimpleSelectField) queryFiled;
				dataStoreFieldMeta.setName( ((SimpleSelectField)queryFiled).getUniqueName() );
				dataStoreFieldMeta.setProperty("calculated", new Boolean(false));
				dataStoreFieldMeta.setProperty("uniqueName", dataMartSelectField.getUniqueName());
				dataStoreFieldMeta.setType(Object.class);
				String format = dataMartSelectField.getPattern();
				if (format != null && !format.trim().equals("")) {
					dataStoreFieldMeta.setProperty("format", format);
				}

				IModelField datamartField = ((AbstractDataSource)statement.getDataSource()).getModelStructure().getField( dataMartSelectField.getUniqueName() );
				String iconCls = datamartField.getPropertyAsString("type");	
				String nature = dataMartSelectField.getNature();
				dataStoreFieldMeta.setProperty("aggregationFunction", dataMartSelectField.getFunction().getName());


				if( nature.equals(QuerySerializationConstants.FIELD_NATURE_MANDATORY_MEASURE)){
					dataStoreFieldMeta.setFieldType(FieldType.MEASURE);
					dataStoreFieldMeta.getProperties().put(PROPERTY_IS_MANDATORY_MEASURE, Boolean.TRUE);
				}
				else
					if( nature.equals(QuerySerializationConstants.FIELD_NATURE_MEASURE)){
						dataStoreFieldMeta.setFieldType(FieldType.MEASURE);
					}
					else
						if( nature.equals(QuerySerializationConstants.FIELD_NATURE_SEGMENT_ATTRIBUTE)){
							dataStoreFieldMeta.setFieldType(FieldType.ATTRIBUTE);
							dataStoreFieldMeta.getProperties().put(PROPERTY_IS_SEGMENT_ATTRIBUTE, Boolean.TRUE);
						}
						else
							if( nature.equals(QuerySerializationConstants.FIELD_NATURE_ATTRIBUTE)){
								dataStoreFieldMeta.setFieldType(FieldType.ATTRIBUTE);
							}
							else
							{
								dataStoreFieldMeta.setFieldType(FieldType.ATTRIBUTE);
							}



			} else if(queryFiled.isCalculatedField()){
				CalculatedSelectField claculatedQueryField = (CalculatedSelectField)queryFiled;
				dataStoreFieldMeta.setName(claculatedQueryField.getAlias());
				dataStoreFieldMeta.setProperty("calculated", new Boolean(true));	
				// FIXME also calculated field must have uniquename for uniformity
				dataStoreFieldMeta.setProperty("uniqueName", claculatedQueryField.getAlias());
				DataSetVariable variable = new DataSetVariable(claculatedQueryField.getAlias(), claculatedQueryField.getType(), claculatedQueryField.getExpression());
				dataStoreFieldMeta.setProperty("variable", variable);	
				dataStoreFieldMeta.setType( variable.getTypeClass() );	

			} else if(queryFiled.isInLineCalculatedField()){
				InLineCalculatedSelectField claculatedQueryField = (InLineCalculatedSelectField)queryFiled;
				dataStoreFieldMeta.setName(claculatedQueryField.getAlias());
				dataStoreFieldMeta.setProperty("calculated", new Boolean(false));	
				// FIXME also calculated field must have uniquename for uniformity
				dataStoreFieldMeta.setProperty("uniqueName", claculatedQueryField.getAlias());
				DataSetVariable variable = new DataSetVariable(claculatedQueryField.getAlias(), claculatedQueryField.getType(), claculatedQueryField.getExpression());
				dataStoreFieldMeta.setProperty("variable", variable);	
				dataStoreFieldMeta.setType( variable.getTypeClass() );	
				
				String nature = queryFiled.getNature();
				if(nature == null) {
					nature = QueryJSONSerializer.getInLinecalculatedFieldNature(claculatedQueryField.getExpression(), aliasSelectedFields);
				}
				dataStoreFieldMeta.setProperty("nature", nature);
				if( nature.equalsIgnoreCase(QuerySerializationConstants.FIELD_NATURE_MANDATORY_MEASURE)||
						nature.equalsIgnoreCase(QuerySerializationConstants.FIELD_NATURE_MEASURE)){
					dataStoreFieldMeta.setFieldType(FieldType.MEASURE);
				}else{
					dataStoreFieldMeta.setFieldType(FieldType.ATTRIBUTE);
				}
			}
			dataStoreFieldMeta.setProperty("visible", new Boolean(queryFiled.isVisible()));	

			dataStoreMeta.addFiedMeta(dataStoreFieldMeta);
		}

		return dataStoreMeta;
	}



	protected DataStore toDataStore(List result) {
		DataStore dataStore;
		MetaData dataStoreMeta;
		Object[] row;

		dataStore = new DataStore();
		dataStoreMeta = getDataStoreMeta( statement.getQuery() );
		dataStore.setMetaData(dataStoreMeta);

		Iterator it = result.iterator();
		while(it.hasNext()) {
			Object o = it.next();

			if (!(o instanceof Object[])){
				row = new Object[1];
				row[0] = o == null? "": o;
			}else{
				row = (Object[])o;
			}


			IRecord record = new Record(dataStore);
			for(int i = 0,  j = 0; i < dataStoreMeta.getFieldCount(); i++) {
				IFieldMetaData fieldMeta = dataStoreMeta.getFieldMeta(i);
				Boolean calculated = (Boolean)fieldMeta.getProperty("calculated");
				if(calculated.booleanValue() == false) {
					Assert.assertTrue(j < row.length, "Impossible to read field [" + fieldMeta.getName() + "] from resultset");
					record.appendField( new Field( row[j] ) );
					if(row[j] != null) fieldMeta.setType(row[j].getClass());
					j++;					
				} else {
					DataSetVariable variable = (DataSetVariable)fieldMeta.getProperty("variable");
					if(variable.getResetType() == DataSetVariable.RESET_TYPE_RECORD) {
						variable.reset();
					}

					record.appendField( new Field( variable.getValue()) );
					if(variable.getValue() != null)  fieldMeta.setType(variable.getValue().getClass());
				}
			}

			processCalculatedFields(record, dataStore);
			dataStore.appendRecord(record);
		}

		return dataStore;
	}

	private void processCalculatedFields(IRecord record, IDataStore dataStore) {
		IMetaData dataStoreMeta;
		List calculatedFieldsMeta;

		dataStoreMeta = dataStore.getMetaData();
		calculatedFieldsMeta = dataStoreMeta.findFieldMeta("calculated", Boolean.TRUE);
		for(int i = 0; i < calculatedFieldsMeta.size(); i++) {
			IFieldMetaData fieldMeta = (IFieldMetaData)calculatedFieldsMeta.get(i);
			DataSetVariable variable = (DataSetVariable)fieldMeta.getProperty("variable");

			ScriptEngineManager scriptManager = new ScriptEngineManager();
			ScriptEngine groovyScriptEngine = scriptManager.getEngineByName("groovy");


			// handle bindings 
			// ... static bindings first
			Iterator it = bindings.keySet().iterator();
			while(it.hasNext()) {
				String bindingName = (String)it.next();
				Object bindingValue = bindings.get(bindingName);
				groovyScriptEngine.put(bindingName, bindingValue);
			}

			// ... then runtime bindings
			Map qFields = new HashMap();
			Map dmFields = new HashMap();
			Object[] columns = new Object[dataStoreMeta.getFieldCount()];
			for(int j = 0; j < dataStoreMeta.getFieldCount(); j++) {
				qFields.put(dataStoreMeta.getFieldMeta(j).getAlias(), record.getFieldAt(j).getValue());
				dmFields.put(dataStoreMeta.getFieldMeta(j).getProperty("uniqueName"), record.getFieldAt(j).getValue());
				columns[j] = record.getFieldAt(j).getValue();
			}

			groovyScriptEngine.put("qFields", qFields); // key = alias
			groovyScriptEngine.put("dmFields", dmFields); // key = id
			groovyScriptEngine.put("fields", qFields); // default key = alias
			groovyScriptEngine.put("columns", columns); // key = col-index

			// show time
			Object calculatedValue = null;
			try {
				calculatedValue = groovyScriptEngine.eval(variable.getExpression());

			} catch (ScriptException ex) {
				calculatedValue = "NA";
				ex.printStackTrace();
			}	

			logger.debug("Field [" + fieldMeta.getName()+ "] is equals to [" + calculatedValue + "]");
			variable.setValue(calculatedValue);

			record.getFieldAt(dataStoreMeta.getFieldIndex(fieldMeta.getName())).setValue(variable.getValue());
		}
	}


	

	public IStatement getStatement() {
		return statement;
	}


	public void setStatement(IStatement statement) {
		this.statement = statement;
	}

	public boolean isAbortOnOverflow() {
		return abortOnOverflow;
	}


	public void setAbortOnOverflow(boolean abortOnOverflow) {
		this.abortOnOverflow = abortOnOverflow;
	}

	public void addBinding(String bindingName, Object bindingValue) {
		bindings.put(bindingName, bindingValue);
	}

	public Object getQuery() {
		return this.statement.getQuery();
	}

	public void setQuery(Object query) {
		this.statement.setQuery((it.eng.qbe.query.Query) query);

	}

	public String getSQLQuery(){
		return statement.getSqlQueryString();
	}

	public IDataSetTableDescriptor persist(String tableName, IDataSource dataSource) {
		IDataSource dataSourceForReading = this.getDataSourceForReading();
		if (dataSourceForReading == null) {
			// dataset is neither flat nor persisted
			dataSourceForReading = this.getDataSource();
		}
		if (dataSource.getLabel().equals(dataSourceForReading.getLabel())) {
			// case when datasource for reading is the same for writing --> use TT table with CREATE AS SELECT
			logger.debug("Datasource for reading is the same for writing --> use TT table with CREATE AS SELECT");
			try {
				String sql = getSQLQuery();
				List<String> fields = getDataSetSelectedFields(statement.getQuery());
				return TemporaryTableManager.createTable(fields, sql, tableName, dataSource);
			} catch (Exception e) {
				logger.error("Error creating the temporary table with name " + tableName, e);
				throw new SpagoBIEngineRuntimeException("Error creating the temporary table with name " + tableName, e);
			}
		} else {
			// case when datasource for reading is not the same for writing --> use super method, i.e. load datastore and write it into destination
			logger.debug("Datasource for reading is not the same for writing --> use super method, i.e. load datastore and write it into destination");
			return super.persist(tableName, dataSource);
		}

	}

	public IDataStore getDomainValues(String fieldName, Integer start, Integer limit, IDataStoreFilter filter) {
		if(isPersisted() || isFlatDataset()){
			int index = this.getStatement().getQuery().getSelectFieldIndex(fieldName);
			String alias = (this.getStatement().getQuery().getSelectFieldByIndex(index)).getAlias();
			return  getDomainValuesForPersistedOrFlat(alias, start, limit, filter);
		}else{
			return getDomainValuesFromTemporaryTable(fieldName, start, limit, filter);
		}
	}
	
	private IDataStore getDomainValuesForPersistedOrFlat(String fieldName, Integer start, Integer limit, IDataStoreFilter filter) {
		StringBuffer buffer = new StringBuffer("Select DISTINCT " + fieldName + " FROM " + getPeristedTableName());
		manageFilterOnDomainValues(buffer, fieldName, filter);
		JDBCDataSet dataset = new JDBCDataSet();
		dataset.setQuery(buffer.toString());
		dataset.setDataSource( getDataSourceForReading() );
		dataset.loadData(start, limit, -1);
		return dataset.getDataStore();
	}
	
	private IDataStore getDomainValuesFromTemporaryTable(String fieldName, Integer start, Integer limit, IDataStoreFilter filter) {
		IDataStore toReturn = null;
		try {
			String tableName = this.getTemporaryTableName();
			logger.debug("Temporary table name : [" + tableName + "]");
			if (tableName == null) {
				logger.error("Temporary table name not set, cannot proceed!!");
				throw new SpagoBIEngineRuntimeException("Temporary table name not set");
			}
			IDataSource dataSource = getDataSource();
			String sql = getSQLQuery();
			IDataSetTableDescriptor tableDescriptor = null;
					
			if (sql.equals(TemporaryTableManager.getLastDataSetSignature(tableName))) {
				// signature matches: no need to create a TemporaryTable
				tableDescriptor = TemporaryTableManager.getLastDataSetTableDescriptor(tableName);
			} else {
				
				List<String> fields = getDataSetSelectedFields(statement.getQuery());
				tableDescriptor = TemporaryTableManager.createTable(fields, sql, tableName, dataSource);
			}
			String filterColumnName = tableDescriptor.getColumnName(fieldName);
			//			StringBuffer buffer = new StringBuffer("Select DISTINCT " + filterColumnName + ", CONCAT(" + filterColumnName + ", ' Description' ) as description FROM " + tableName);
			StringBuffer buffer = new StringBuffer("Select DISTINCT " + filterColumnName + " FROM " + tableName);
			manageFilterOnDomainValues(buffer, fieldName, tableDescriptor, filter);
			String sqlStatement = buffer.toString();
			toReturn = TemporaryTableManager.queryTemporaryTable(sqlStatement, dataSource, start, limit);
			toReturn.getMetaData().changeFieldAlias(0, fieldName);
		} catch (Exception e) {
			logger.error("Error loading the domain values for the field " + fieldName, e);
			throw new SpagoBIEngineRuntimeException("Error loading the domain values for the field "+fieldName, e);

		}
		return toReturn;
	}

	private void manageFilterOnDomainValues(StringBuffer buffer,
			String fieldName, IDataSetTableDescriptor tableDescriptor, IDataStoreFilter filter) {
		if (filter != null) {
			String filterColumnName = tableDescriptor.getColumnName(fieldName);
			if (filterColumnName == null) {
				throw new SpagoBIRuntimeException("Field name [" + fieldName + "] not found");
			}
			String columnName = tableDescriptor.getColumnName(fieldName);
			Class clazz = tableDescriptor.getColumnType(fieldName);
			String value = getFilterValue(filter.getValue(), clazz);
			IConditionalOperator conditionalOperator = (IConditionalOperator) HQLStatement.conditionalOperators.get(filter.getOperator());
			String temp = conditionalOperator.apply(columnName, new String[] { value });
			buffer.append(" WHERE " + temp);
		}
	}
	
	private void manageFilterOnDomainValues(StringBuffer buffer, String fieldName, IDataStoreFilter filter) {
		if (filter != null) {
			
			//get The fieldByAlias
			IMetaData md = getMetadata();
			IFieldMetaData fmd = null;
			int i=0;
			if(md!=null){
				for(i=0; i<md.getFieldCount(); i++){
					fmd = md.getFieldMeta(i);
					if(fieldName.equals(fmd.getAlias()) || fieldName.equals(fmd.getName())){
						break;
					}
				}
			}
			if(i<md.getFieldCount()){
				Class clazz = fmd.getType();
				String value = getFilterValue(filter.getValue(), clazz);
				IConditionalOperator conditionalOperator = (IConditionalOperator) HQLStatement.conditionalOperators.get(filter.getOperator());
				String temp = conditionalOperator.apply(fieldName, new String[] { value });
				buffer.append(" WHERE " + temp);
			}else{
				throw new SpagoBIRuntimeException("Field name [" + fieldName + "] not found");
			}

			
			

		}
	}

	private String getFilterValue(String value, Class clazz) {
		String toReturn = null;
		if ( String.class.isAssignableFrom(clazz) ) {
			value = StringUtils.escapeQuotes(value);
			toReturn = StringUtils.bound(value, "'");
		} else if ( Number.class.isAssignableFrom(clazz) ) {
			toReturn = value;
		} else if ( Boolean.class.isAssignableFrom(clazz) ) {
			toReturn = value;
		} else {
			// TODO manage other types, such as date and timestamp
			throw new SpagoBIRuntimeException("Unsupported operation: cannot filter on a fild type " + clazz.getName());
		}
		return toReturn;
	}

	private String getUserId() {
		Map userProfileAttrs = getUserProfileAttributes();
		String userId = null;
		if (userProfileAttrs != null) {
			userId = (String) userProfileAttrs.get(SsoServiceInterface.USER_ID);
		}
		return userId;
	}

	/**
	 * Get the relation between the fields in the select clause
	 * of the Qbe Query and its sql representation
	 * @param sqlQuery Qbe Query translated in sql
	 * @param qbeQuery Qbe Query 
	 * @return
	 */
	//	private IDataSetTableDescriptor getDataSetTableDescriptor(String sqlQuery, Query qbeQuery, String tableName){
	//		DataSetTableDescriptor dataSetTableDescriptor = new DataSetTableDescriptor();
	//		
	//		List<String[]> selectFieldsColumn = SqlUtils.getSelectFields(sqlQuery);
	//		List<ISelectField> selectFieldsNames = qbeQuery.getSelectFields(true);
	//		for(int i=0; i<selectFieldsColumn.size(); i++){
	//			ISelectField selectField = selectFieldsNames.get(i);
	//			String fieldName = selectField.getAlias();
	//			String columnName = selectFieldsColumn.get(i)[1];
	//			statement.getDataSource().getModelStructure().getField(selectField.);
	//			Class c = null;
	//
	//			dataSetTableDescriptor.addField(fieldName, columnName, c);
	//		}
	//		dataSetTableDescriptor.setTableName(tableName);
	//		return dataSetTableDescriptor;
	//	}

	private List<String> getDataSetSelectedFields(Query qbeQuery){
		List<String> toReturn = new ArrayList<String>();
		List<ISelectField> selectFieldsNames = qbeQuery.getSelectFields(true);
		for (int i=0; i<selectFieldsNames.size(); i++){
			ISelectField selectField = selectFieldsNames.get(i);
			toReturn.add(selectField.getName());
		}
		return toReturn;
	}

	/**
	 * Build a datasource.. We need this object
	 * to build a JDBCDataSet
	 * @return
	 */
	public IDataSource getDataSource(){
		if(dataSource==null){
			dataSource = new DataSource();
			ConnectionDescriptor connectionDescriptor = ((AbstractDataSource)statement.getDataSource()).getConnection();
			dataSource.setHibDialectName(connectionDescriptor.getDialect());
			dataSource.setHibDialectClass(connectionDescriptor.getDialect());
			dataSource.setDriver(connectionDescriptor.getDriverClass());
			dataSource.setJndi(connectionDescriptor.getJndiName());
			dataSource.setLabel(connectionDescriptor.getName());
			dataSource.setPwd(connectionDescriptor.getPassword());
			dataSource.setUrlConnection(connectionDescriptor.getUrl());
			dataSource.setUser(connectionDescriptor.getUsername());
		}
		return dataSource;
	}

	public IMetaData getMetadata() {
		return getDataStoreMeta(statement.getQuery());
	}

	public String getSignature() {
		return getSQLQuery();
	}


	public Map getUserProfileAttributes() {
		return userProfileAttributes;
	}

	public void setUserProfileAttributes(Map attributes) {
		this.userProfileAttributes = attributes;

	}

	@Override
	public void setParamsMap(Map paramsMap) {
		this.getStatement().setParameters(paramsMap);
	}

	@Override
	public Map getParamsMap() {
		return this.getStatement().getParameters();
	}

	public IDataStore decode(IDataStore datastore) {
		return datastore;
	}

	public IDataStore test(int offset, int fetchSize, int maxResults) {
		this.loadData(offset, fetchSize, maxResults);
		return getDataStore();
	}

	public IDataStore test() {
		loadData();
		return getDataStore();
	}

	public void setMetadata(IMetaData metadata) {
		// TODO Auto-generated method stub

	}

	public boolean isCalculateResultNumberOnLoadEnabled() {
		return calculateResultNumberOnLoad;
	}

	public void setCalculateResultNumberOnLoad(boolean enabled) {
		calculateResultNumberOnLoad = enabled;
	}
	
	public void updateParameters(it.eng.qbe.query.Query query, Map parameters) {
		logger.debug("IN");
		List whereFields = query.getWhereFields();
		Iterator whereFieldsIt = whereFields.iterator();
		while (whereFieldsIt.hasNext()) {
			WhereField whereField = (WhereField) whereFieldsIt.next();
			if (whereField.isPromptable()) {
				String key = getParameterKey(whereField.getRightOperand().values[0]);
				if (key != null) {
					String parameterValues = (String) parameters.get(key);
					if (parameterValues != null) {
						String[] promptValues = new String[] {parameterValues}; // TODO how to manage multi-values prompts?
						logger.debug("Read prompts " + promptValues + " for promptable filter " + whereField.getName() + ".");
						whereField.getRightOperand().lastValues = promptValues;
					}
				}
			}
		}
		List havingFields = query.getHavingFields();
		Iterator havingFieldsIt = havingFields.iterator();
		while (havingFieldsIt.hasNext()) {
			HavingField havingField = (HavingField) havingFieldsIt.next();
			if (havingField.isPromptable()) {
				String key = getParameterKey(havingField.getRightOperand().values[0]);
				if (key != null) {
					String parameterValues = (String) parameters.get(key);
					if (parameterValues != null) {
						String[] promptValues = new String[] {parameterValues}; // TODO how to manage multi-values prompts?
						logger.debug("Read prompt value " + promptValues + " for promptable filter " + havingField.getName() + ".");
						havingField.getRightOperand().lastValues = promptValues; 
					}
				}
			}
		}
		logger.debug("OUT");
	}
	
	protected String getParameterKey(String fieldValue) {
		int beginIndex = fieldValue.indexOf("P{");
		int endIndex = fieldValue.indexOf("}");
		if (beginIndex > 0 && endIndex > 0 && endIndex > beginIndex) {
			return fieldValue.substring(beginIndex + 2, endIndex);
		} else {
			return null;
		}
	}

	
	
	/**
	 * Adjusts the metadata of the datastore retrieved by a JDBCDataSet, since
	 * executed JDBC dataset does not contain correct metadata (name, alias,
	 * attribute/measure) therefore we need to merge metadata
	 * 
	 * @param jdbcMetadata the metadata retrieved by executing the JDBC dataset
	 * @param qbeQueryMetaData the metadata of the Qbe query
	 */
	protected IMetaData mergeMetadata(IMetaData jdbcMetadata, IMetaData qbeQueryMetaData) {
		int count = jdbcMetadata.getFieldCount();
		for (int i = 0; i < count; i++) {
			IFieldMetaData jdbcFieldMeta = jdbcMetadata.getFieldMeta(i);
			// positional matching between the 2 metadata
			IFieldMetaData qbeFieldMeta = qbeQueryMetaData.getFieldMeta(i);
			jdbcFieldMeta.setFieldType(qbeFieldMeta.getFieldType());
			jdbcFieldMeta.setName(qbeFieldMeta.getName());
			jdbcFieldMeta.setAlias(qbeFieldMeta.getAlias());
		}
		return jdbcMetadata;
	}
	
}
