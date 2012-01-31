/**
 * eobjects.org DataCleaner
 * Copyright (C) 2010 eobjects.org
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.eobjects.datacleaner.actions;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.eobjects.analyzer.beans.BooleanAnalyzer;
import org.eobjects.analyzer.beans.DateAndTimeAnalyzer;
import org.eobjects.analyzer.beans.NumberAnalyzer;
import org.eobjects.analyzer.beans.StringAnalyzer;
import org.eobjects.analyzer.beans.api.Analyzer;
import org.eobjects.analyzer.beans.stringpattern.PatternFinderAnalyzer;
import org.eobjects.analyzer.beans.valuedist.ValueDistributionAnalyzer;
import org.eobjects.analyzer.configuration.AnalyzerBeansConfiguration;
import org.eobjects.analyzer.connection.Datastore;
import org.eobjects.analyzer.data.InputColumn;
import org.eobjects.analyzer.job.builder.AnalysisJobBuilder;
import org.eobjects.analyzer.job.builder.AnalyzerJobBuilder;
import org.eobjects.analyzer.util.ReflectionUtils;
import org.eobjects.datacleaner.guice.DCModule;
import org.eobjects.datacleaner.guice.Nullable;
import org.eobjects.datacleaner.user.QuickAnalysisStrategy;
import org.eobjects.datacleaner.user.UserPreferences;
import org.eobjects.datacleaner.util.WidgetUtils;
import org.eobjects.metamodel.schema.Column;
import org.eobjects.metamodel.schema.Table;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * ActionListener for performing a quick analysis based on standard analyzers.
 * 
 * @author Kasper Sørensen
 */
public class QuickAnalysisActionListener implements ActionListener {

	private final Datastore _datastore;
	private final Table _table;
	private final Column[] _columns;
	private final DCModule _parentModule;
	private final UserPreferences _userPreferences;
	private final AnalyzerBeansConfiguration _configuration;

	@Inject
	protected QuickAnalysisActionListener(Datastore datastore, @Nullable Table table, @Nullable Column[] columns,
			DCModule parentModule, UserPreferences userPreferences, AnalyzerBeansConfiguration configuration) {
		_datastore = datastore;
		_table = table;
		_columns = columns;
		_parentModule = parentModule;
		_userPreferences = userPreferences;
		_configuration = configuration;
	}

	public Column[] getColumns() {
		if (_columns == null) {
			return _table.getColumns();
		}
		return _columns;
	}

	public Table getTable() {
		if (_table == null) {
			return _columns[0].getTable();
		}
		return _table;
	}

	@Override
	public void actionPerformed(ActionEvent event) {
		final AnalysisJobBuilder ajb = new AnalysisJobBuilder(_configuration);
		ajb.setDatastore(_datastore);

		final List<InputColumn<?>> booleanColumns = new ArrayList<InputColumn<?>>();
		final List<InputColumn<?>> stringColumns = new ArrayList<InputColumn<?>>();
		final List<InputColumn<?>> numberColumns = new ArrayList<InputColumn<?>>();
		final List<InputColumn<?>> dateTimeColumns = new ArrayList<InputColumn<?>>();

		for (Column column : getColumns()) {
			ajb.addSourceColumn(column);
			InputColumn<?> inputColumn = ajb.getSourceColumnByName(column.getName());
			Class<?> dataType = inputColumn.getDataType();
			if (ReflectionUtils.isBoolean(dataType)) {
				booleanColumns.add(inputColumn);
			} else if (ReflectionUtils.isNumber(dataType)) {
				numberColumns.add(inputColumn);
			} else if (ReflectionUtils.isDate(dataType)) {
				dateTimeColumns.add(inputColumn);
			} else if (ReflectionUtils.isString(dataType)) {
				stringColumns.add(inputColumn);
			}
		}

		if (!booleanColumns.isEmpty()) {
			// boolean analyzer contains combination matrices, so all columns
			// are added to a single analyzer job.
			ajb.addAnalyzer(BooleanAnalyzer.class).addInputColumns(booleanColumns);
		}
		if (!numberColumns.isEmpty()) {
			createAnalyzers(ajb, NumberAnalyzer.class, numberColumns);
		}
		if (!dateTimeColumns.isEmpty()) {
			createAnalyzers(ajb, DateAndTimeAnalyzer.class, dateTimeColumns);
		}
		if (!stringColumns.isEmpty()) {
			createAnalyzers(ajb, StringAnalyzer.class, stringColumns);
		}

		try {
			if (!ajb.isConfigured(true)) {
				throw new IllegalStateException("Unknown job configuration issue!");
			}

			Injector injector = Guice.createInjector(new DCModule(_parentModule, ajb));

			RunAnalysisActionListener actionListener = injector.getInstance(RunAnalysisActionListener.class);
			actionListener.actionPerformed(event);
		} catch (Exception e) {
			WidgetUtils.showErrorMessage("Error", "Could not perform quick analysis on table " + _table.getName(), e);
		}

	}

	/**
	 * Registers analyzers and up to 4 columns per analyzer. This restriction is
	 * to ensure that results will be nicely readable. A table might contain
	 * hundreds of columns.
	 * 
	 * @param ajb
	 * @param analyzerClass
	 * @param columns
	 */
	private void createAnalyzers(AnalysisJobBuilder ajb, Class<? extends Analyzer<?>> analyzerClass,
			List<InputColumn<?>> columns) {
		final QuickAnalysisStrategy quickAnalysisStrategy = _userPreferences.getQuickAnalysisStrategy();
		final int columnsPerAnalyzer = quickAnalysisStrategy.getColumnsPerAnalyzer();

		AnalyzerJobBuilder<?> analyzerJobBuilder = ajb.addAnalyzer(analyzerClass);
		int columnCount = 0;
		for (InputColumn<?> inputColumn : columns) {
			if (columnCount == columnsPerAnalyzer) {
				analyzerJobBuilder = ajb.addAnalyzer(analyzerClass);
				columnCount = 0;
			}
			analyzerJobBuilder.addInputColumn(inputColumn);

			if (quickAnalysisStrategy.isIncludeValueDistribution()) {
				ajb.addAnalyzer(ValueDistributionAnalyzer.class).addInputColumn(inputColumn);
			}
			if (inputColumn.getDataType() == String.class && quickAnalysisStrategy.isIncludePatternFinder()) {
				ajb.addAnalyzer(PatternFinderAnalyzer.class).addInputColumn(inputColumn);
			}
			columnCount++;
		}
	}
}
