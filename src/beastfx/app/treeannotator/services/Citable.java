package beastfx.app.treeannotator.services;

import beast.base.core.Citation;

public interface Citable {
    default String getCitation() {
        Citation citation = this.getClass().getAnnotation(Citation.class);
        if (citation != null) {
            return String.format("%s\n [%s]",
                    citation.value(),
                    citation.DOI());
        }
        return "";
    }
}
