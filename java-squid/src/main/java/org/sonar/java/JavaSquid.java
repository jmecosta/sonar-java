/*
 * Sonar Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.utils.TimeProfiler;
import org.sonar.graph.DirectedGraph;
import org.sonar.graph.DirectedGraphAccessor;
import org.sonar.java.ast.AstScanner;
import org.sonar.java.ast.visitors.FileLinesVisitor;
import org.sonar.java.bytecode.BytecodeScanner;
import org.sonar.java.bytecode.visitor.*;
import org.sonar.squid.api.*;
import org.sonar.squid.indexer.QueryByType;
import org.sonar.squid.indexer.SquidIndex;

import javax.annotation.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class JavaSquid implements DirectedGraphAccessor<SourceCode, SourceCodeEdge>, SourceCodeSearchEngine {

  private final SquidIndex squidIndex;
  private final AstScanner astScanner;
  private final BytecodeScanner bytecodeScanner;
  private final DirectedGraph<SourceCode, SourceCodeEdge> graph = new DirectedGraph<SourceCode, SourceCodeEdge>();

  @VisibleForTesting
  public JavaSquid(JavaConfiguration conf, CodeVisitor... visitors) {
    this(conf, null, visitors);
  }

  public JavaSquid(JavaConfiguration conf, @Nullable FileLinesContextFactory fileLinesContextFactory, CodeVisitor... visitors) {
    astScanner = JavaAstScanner.create(conf);
    if (fileLinesContextFactory != null) {
      astScanner.accept(new FileLinesVisitor(fileLinesContextFactory));
    }

    squidIndex = (SquidIndex) astScanner.getIndex(); // TODO unchecked cast

    bytecodeScanner = new BytecodeScanner(squidIndex);
    bytecodeScanner.accept(new DITVisitor());
    bytecodeScanner.accept(new RFCVisitor());
    bytecodeScanner.accept(new NOCVisitor());
    bytecodeScanner.accept(new LCOM4Visitor(conf.getFieldsToExcludeFromLcom4Calculation()));
    bytecodeScanner.accept(new DependenciesVisitor(graph));

    // External visitors (typically Check ones):
    for (CodeVisitor visitor : visitors) {
      astScanner.accept(visitor);
      bytecodeScanner.accept(visitor);
    }
  }

  @VisibleForTesting
  public void scan(Collection<File> sourceDirectories, Collection<File> bytecodeFilesOrDirectories) {
    List<File> sourceFiles = Lists.newArrayList();
    for (File dir : sourceDirectories) {
      sourceFiles.addAll(FileUtils.listFiles(dir, new String[] {"java"}, true));
    }
    scanFiles(sourceFiles, bytecodeFilesOrDirectories);
  }

  public void scanFiles(Collection<File> sourceFiles, Collection<File> bytecodeFilesOrDirectories) {
    // TODO
    scanSources(sourceFiles);
    scanBytecode(bytecodeFilesOrDirectories);

    // SourceProject project = (SourceProject) squidIndex.search(new QueryByType(SourceProject.class)).iterator().next();
    // SourceCodeTreeDecorator decorator = new SourceCodeTreeDecorator(project);
    // decorator.decorateWith(Metric.values());
  }

  private void scanSources(Collection<File> sourceFiles) {
    TimeProfiler profiler = new TimeProfiler(getClass()).start("Java AST scan");
    astScanner.scan(sourceFiles);
    profiler.stop();
  }

  private void scanBytecode(Collection<File> bytecodeFilesOrDirectories) {
    TimeProfiler profiler = new TimeProfiler(getClass()).start("Java bytecode scan");
    bytecodeScanner.scan(bytecodeFilesOrDirectories);
    profiler.stop();
  }

  public SquidIndex getIndex() {
    return squidIndex;
  }

  public DirectedGraph<SourceCode, SourceCodeEdge> getGraph() {
    return graph;
  }

  public SourceCodeEdge getEdge(SourceCode from, SourceCode to) {
    return graph.getEdge(from, to);
  }

  public boolean hasEdge(SourceCode from, SourceCode to) {
    return graph.hasEdge(from, to);
  }

  public Set<SourceCode> getVertices() {
    return graph.getVertices();
  }

  public Collection<SourceCodeEdge> getOutgoingEdges(SourceCode from) {
    return graph.getOutgoingEdges(from);
  }

  public Collection<SourceCodeEdge> getIncomingEdges(SourceCode to) {
    return graph.getIncomingEdges(to);
  }

  public List<SourceCodeEdge> getEdges(Collection<SourceCode> sourceCodes) {
    return graph.getEdges(sourceCodes);
  }

  public Collection<SourceCode> search(QueryByType queryByType) {
    return squidIndex.search(queryByType);
  }

  public SourceCode search(String key) {
    return squidIndex.search(key);
  }

  public Collection<SourceCode> search(Query... query) {
    return squidIndex.search(query);
  }

}