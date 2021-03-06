/*******************************************************************************
 * Copyright (c) 2011, 2015 Oracle and/or its affiliates. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0
 * which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     10/15/2010-2.2 Guy Pelletier
 *       - 322008: Improve usability of additional criteria applied to queries at the session/EM
 ******************************************************************************/
package org.eclipse.persistence.testing.models.jpa.advanced.additionalcriteria;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.Basic;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.NamedNativeQuery;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

import org.eclipse.persistence.annotations.AdditionalCriteria;

import static javax.persistence.CascadeType.PERSIST;

@Entity
@Table(name="JPA_AC_SCHOOL")
@AdditionalCriteria("this.name LIKE 'Ottawa%'")
// A named native query will not append the additional criteria.
@NamedNativeQuery(
    name="findAllSQLSchools",
    query="select * from JPA_AC_SCHOOL",
    resultClass=org.eclipse.persistence.testing.models.jpa.advanced.additionalcriteria.School.class
)
@NamedQuery(
    name="findJPQLSchools",
    query="SELECT s from School s"
)
public class School {
    @Id
    @GeneratedValue(generator="AC_SCHOOL_SEQ")
    @SequenceGenerator(name="AC_SCHOOL_SEQ", allocationSize=25)
    public Integer id;

    @Basic
    public String name;

    @OneToMany(mappedBy="school", cascade=PERSIST)
    public List<Student> students;

    public School() {
        students = new ArrayList<Student>();
    }

    public void addStudent(Student student) {
        students.add(student);
        student.setSchool(this);
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Student> getStudents() {
        return students;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setStudents(List<Student> students) {
        this.students = students;
    }
}
