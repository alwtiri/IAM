package com.enterprise.iam.core.sod.application;

import com.enterprise.iam.core.sod.domain.SodRule;
import java.util.List;

public interface SodStore {

    List<SodRule> rules();
}
