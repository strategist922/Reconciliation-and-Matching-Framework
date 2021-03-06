// WARNING: DO NOT EDIT THIS FILE. THIS FILE IS MANAGED BY SPRING ROO.
// You may push code into the target .java compilation unit if you wish to edit any member(s).

package org.kew.rmf.matchconf;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

privileged aspect Wire_Roo_Finder {
    
    public static TypedQuery<Wire> Wire.findWiresByMatcher(Matcher matcher) {
        if (matcher == null) throw new IllegalArgumentException("The matcher argument is required");
        EntityManager em = Wire.entityManager();
        TypedQuery<Wire> q = em.createQuery("SELECT o FROM Wire AS o WHERE o.matcher = :matcher", Wire.class);
        q.setParameter("matcher", matcher);
        return q;
    }
    
}
