package com.jozufozu.flywheel.api.struct;

import com.jozufozu.flywheel.core.model.Model;

public interface Batched<S> extends StructType<S> {

	BatchingTransformer<S> getTransformer(Model model);

	@Override
	default Batched<S> asBatched() {
		return this;
	}
}
