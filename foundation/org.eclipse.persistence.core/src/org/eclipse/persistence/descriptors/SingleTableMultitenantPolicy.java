/*******************************************************************************
 * Copyright (c) 2011 Oracle. All rights reserved.
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 
 * which accompanies this distribution. 
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at 
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     09/09/2011-2.3.1 Guy Pelletier 
 *       - 356197: Add new VPD type to MultitenantType 
 ******************************************************************************/  
package org.eclipse.persistence.descriptors;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.exceptions.DescriptorException;
import org.eclipse.persistence.exceptions.ValidationException;
import org.eclipse.persistence.expressions.Expression;
import org.eclipse.persistence.expressions.ExpressionBuilder;
import org.eclipse.persistence.internal.helper.DatabaseField;
import org.eclipse.persistence.internal.sessions.AbstractRecord;
import org.eclipse.persistence.internal.sessions.AbstractSession;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.tools.schemaframework.TableDefinition;

/**
 * A single table "striped" multitenant policy.
 * 
 * @author Guy Pelletier
 * @since EclipseLink 2.3.1
 */
public class SingleTableMultitenantPolicy implements MultitenantPolicy {
    protected boolean includeTenantCriteria;
    protected ClassDescriptor descriptor;
    protected Map<DatabaseField, String> tenantDiscriminatorFields;
    
    public SingleTableMultitenantPolicy(ClassDescriptor desc) {
        descriptor = desc;
        includeTenantCriteria = true;
        tenantDiscriminatorFields = new HashMap(5);
    }
    
    /**
     * INTERNAL:
     * Add the tenant discriminator fields to the row.
     */
    public void addFieldsToRow(AbstractRecord row, AbstractSession session) {
        for (DatabaseField discriminatorField : tenantDiscriminatorFields.keySet()) {
            String property = tenantDiscriminatorFields.get(discriminatorField);
            Object propertyValue = session.getProperty(property);
            row.put(discriminatorField, propertyValue);
        }
    }
    
    /**
     * INTERNAL:
     */
    public void addToTableDefinition(TableDefinition tableDefinition) {
        // Does nothing at this level.
    }
    
    /**
     * INTERNAL:
     */
    public MultitenantPolicy clone(ClassDescriptor descriptor) {
        SingleTableMultitenantPolicy clonedPolicy = new SingleTableMultitenantPolicy(descriptor);
        clonedPolicy.includeTenantCriteria = includeTenantCriteria;
        clonedPolicy.tenantDiscriminatorFields = tenantDiscriminatorFields;
        return clonedPolicy;
    }
    
    /**
     * INTERNAL:
     */
    public ClassDescriptor getDescriptor() {
        return descriptor;
    }
    
    /**
     * INTERNAL:
     * Add a tenant discriminator field to the policy.
     */
    public void addTenantDiscriminatorField(String property, DatabaseField field) {
        if (tenantDiscriminatorFields.containsKey(field)) {
            String currentProperty = tenantDiscriminatorFields.get(field);
            
            if (! currentProperty.equals(property)) {
                // Adding a different property for the same field is not 
                // allowed. If it is the same we'll just ignore it.
                throw ValidationException.multipleContextPropertiesForSameTenantDiscriminatorFieldSpecified(getDescriptor().getJavaClassName(), field.getQualifiedName(), currentProperty, property);
            }
        } else {
            tenantDiscriminatorFields.put(field, property);
        }
    }

    /**
     * INTERNAL:
     */
    public Map<DatabaseField, String> getTenantDiscriminatorFields() {
        return tenantDiscriminatorFields;
    }
    
    /**
     * INTERNAL:
     * Return if this descriptor has specified some tenant discriminator fields.
     */
    public boolean hasTenantDiscriminatorFields() {
        return ! tenantDiscriminatorFields.isEmpty();
    }
    
    /**
     * INTERNAL:
     * Initialize the mappings as a separate step.
     * This is done as a separate step to ensure that inheritance has been first resolved.
     */
    public void initialize(AbstractSession session) throws DescriptorException {
        if (hasTenantDiscriminatorFields()) {
            for (DatabaseField discriminatorField : tenantDiscriminatorFields.keySet()) {
                DatabaseMapping mapping = getDescriptor().getObjectBuilder().getMappingForField(discriminatorField);
                
                if (mapping != null && ! mapping.isReadOnly()) {
                    throw ValidationException.nonReadOnlyMappedTenantDiscriminatorField(getDescriptor().getJavaClassName(), discriminatorField.getQualifiedName());
                }
            }
        }
    }
    
    /**
     * INTERNAL:
     * Subclasses that need to add field to an expresison should override this method.
     */
    public void postInitialize(AbstractSession session) {
        if (includeTenantCriteria) {
            Expression expression = getDescriptor().getQueryManager().getAdditionalJoinExpression();
            ExpressionBuilder builder = (expression == null) ? new ExpressionBuilder() : expression.getBuilder();
        
            for (DatabaseField discriminatorField : tenantDiscriminatorFields.keySet()) {
                String property = tenantDiscriminatorFields.get(discriminatorField);
                // Add the tenant discriminator field context property as the parameter.
                // Do not initialize the database field with the property as it could be tenant.id 
                // and we do not want to de-qualify it.
                DatabaseField newField = new DatabaseField();
                newField.setName(property, session.getPlatform());
                Expression tenantIdExpression = builder.and(builder.getField(discriminatorField).equal(builder.getProperty(newField)));
                
                if (expression == null) {
                    expression = tenantIdExpression;
                } else {
                    expression = expression.and(tenantIdExpression);
                }
            }
            
            getDescriptor().getQueryManager().setAdditionalJoinExpression(expression);
        }
    }
    
    /**
     * INTERNAL:
     * Allow the descriptor to initialize any dependencies on this session.
     */
    public void preInitialize(AbstractSession session) throws DescriptorException {
        for (DatabaseField discriminatorField : tenantDiscriminatorFields.keySet()) {                
            getDescriptor().getFields().add(getDescriptor().buildField(discriminatorField));
        }
    }
    
    /**
     * INTERNAL:
     */
    public void setDescriptor(ClassDescriptor descriptor) {
        this.descriptor = descriptor;
    }
    
    /**
     * ADVANCED:
     * Boolean used to indicate if the database requires the tenant criteria to
     * be added to the SELECT, UPDATE, and DELETE queries. By default this is
     * done but when set to false the queries will not be modified and it will
     * be up to the application or database to ensure that the correct criteria 
     * is applied to all queries.
     * 
     * @see org.eclipse.persistence.annotations.Multitenant
     */
    public void setIncludeTenantCriteria(boolean includeTenantCriteria) {
        this.includeTenantCriteria = includeTenantCriteria;
    }
    
    /**
     * INTERNAL:
     */
    public void setTenantDiscriminatorFields(Map<DatabaseField, String> tenantDiscriminatorFields) {
        this.tenantDiscriminatorFields = tenantDiscriminatorFields;
    }
}
