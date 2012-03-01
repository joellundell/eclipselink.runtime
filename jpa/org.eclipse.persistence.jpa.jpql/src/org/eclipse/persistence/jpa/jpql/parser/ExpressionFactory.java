/*******************************************************************************
 * Copyright (c) 2006, 2011 Oracle. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0
 * which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Oracle - initial API and implementation
 *
 ******************************************************************************/
package org.eclipse.persistence.jpa.jpql.parser;

import java.util.Arrays;
import org.eclipse.persistence.jpa.jpql.WordParser;
import org.eclipse.persistence.jpa.jpql.spi.JPAVersion;

/**
 * An <code>ExpressionFactory</code> is responsible to parse a portion of JPQL query that starts
 * with one of the factory's identifiers.
 *
 * @version 2.4
 * @since 2.3
 * @author Pascal Filion
 */
@SuppressWarnings("nls")
public abstract class ExpressionFactory implements Comparable<ExpressionFactory> {

	/**
	 * The {@link ExpressionRegistry} with which this {@link ExpressionFactory} was registered.
	 */
	private ExpressionRegistry expressionRegistry;

	/**
	 * The unique identifier of this {@link ExpressionFactory}.
	 */
	private String id;

	/**
	 * The JPQL identifiers handled by this factory.
	 */
	private String[] identifiers;

	/**
	 * Creates a new <code>ExpressionFactory</code>.
	 *
	 * @param id The unique identifier of this <code>ExpressionFactory</code>
	 * @param identifiers The JPQL identifiers handled by this factory
	 * @exception NullPointerException The given unique identifier cannot be <code>null</code> or
	 * the list of JPQL identifiers was <code>null</code>
	 */
	protected ExpressionFactory(String id, String... identifiers) {
		super();
		initialize(id, identifiers);
	}

	/**
	 * Creates a new {@link Expression}.
	 *
	 * @param parent The parent expression
	 * @param wordParser The text to parse based on the current position of the cursor
	 * @param word The current word to parse
	 * @param queryBNF The BNF grammar that was used to identifier this factory to be capable to
	 * parse a portion of the query
	 * @param expression During the parsing, it is possible the first part of an expression was
	 * parsed which needs to be used as a sub-expression of the newly created expression
	 * @return A new <code>Expression</code> representing a portion or the totality of the given text
	 */
	protected abstract AbstractExpression buildExpression(AbstractExpression parent,
	                                                      WordParser wordParser,
	                                                      String word,
	                                                      JPQLQueryBNF queryBNF,
	                                                      AbstractExpression expression,
	                                                      boolean tolerant);

	/**
	 * {@inheritDoc}
	 */
	public final int compareTo(ExpressionFactory expressionFactory) {
		return id.compareTo(expressionFactory.getId());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final boolean equals(Object object) {

		if (this == object) {
			return true;
		}

		ExpressionFactory factory = (ExpressionFactory) object;
		return id.equals(factory.id);
	}

	/**
	 * Returns the registry containing the {@link JPQLQueryBNF JPQLQueryBNFs} and the {@link
	 * org.eclipse.persistence.jpa.jpql.parser.ExpressionFactory ExpressionFactories} that are used
	 * to properly parse a JPQL query.
	 *
	 * @return The registry containing the information related to the JPQL grammar
	 */
	public final ExpressionRegistry getExpressionRegistry() {
		return expressionRegistry;
	}

	/**
	 * Returns the unique identifier of this <code>ExpressionFactory</code>.
	 *
	 * @return The identifier used to register this {@link ExpressionFactory} with {@link
	 * ExpressionRegistry}
	 */
	public final String getId() {
		return id;
	}

	/**
	 * Returns the supported JPA version.
	 *
	 * @return The version for which this factory can support
	 */
	public JPAVersion getVersion() {
		return JPAVersion.VERSION_1_0;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final int hashCode() {
		return id.hashCode();
	}

	/**
	 * Returns the JPQL identifiers handled by this factory.
	 *
	 * @return The list of JPQL identifiers this factory knows how to parse
	 */
	public final String[] identifiers() {
		return identifiers;
	}

	private void initialize(String id, String... identifiers) {

		if (id == null) {
			throw new NullPointerException("The unique identifier of this ExpressionFactory cannot be null");
		}

		if (identifiers == null) {
			throw new NullPointerException("The list of supported JPQL identifiers cannot be null");
		}

		this.id = id;
		this.identifiers = identifiers;
	}

	/**
	 * Sets the backpointer to the {@link ExpressionRegistry} with which this {@link JPQLQueryBNF}
	 * was registered.
	 *
	 * @param expressionRegistry The registry for a given JPQL grammar
	 */
	final void setExpressionRegistry(ExpressionRegistry expressionRegistry) {
		this.expressionRegistry = expressionRegistry;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(getClass().getSimpleName());
		sb.append("(id=");
		sb.append(id);
		sb.append(", identifiers=");
		sb.append(Arrays.toString(identifiers));
		sb.append(")");
		return sb.toString();
	}
}