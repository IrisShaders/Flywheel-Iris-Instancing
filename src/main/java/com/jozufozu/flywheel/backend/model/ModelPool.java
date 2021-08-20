package com.jozufozu.flywheel.backend.model;

import java.util.ArrayList;
import java.util.List;

import com.jozufozu.flywheel.backend.Backend;
import com.jozufozu.flywheel.backend.gl.GlPrimitive;
import com.jozufozu.flywheel.backend.gl.attrib.VertexFormat;
import com.jozufozu.flywheel.backend.gl.buffer.GlBuffer;
import com.jozufozu.flywheel.backend.gl.buffer.GlBufferType;
import com.jozufozu.flywheel.backend.gl.buffer.MappedBuffer;
import com.jozufozu.flywheel.backend.gl.buffer.MappedGlBuffer;
import com.jozufozu.flywheel.core.model.IModel;
import com.jozufozu.flywheel.util.AttribUtil;
import com.jozufozu.flywheel.util.StringUtil;

public class ModelPool {

	protected final VertexFormat format;

	private final List<PooledModel> models = new ArrayList<>();

	private final List<PooledModel> pendingUpload = new ArrayList<>();

	private final GlBuffer vbo;
	private int bufferSize;

	private int vertices;

	private boolean dirty;
	private boolean anyToRemove;

	public ModelPool(VertexFormat format, int initialSize) {
		this.format = format;
		bufferSize = initialSize;

		vbo = new MappedGlBuffer(GlBufferType.ARRAY_BUFFER);

		vbo.bind();
		vbo.alloc(bufferSize);
		vbo.unbind();
	}

	/**
	 * Allocate a model in the arena.
	 *
	 * @param model The model to allocate.
	 * @return A handle to the allocated model.
	 */
	public PooledModel alloc(IModel model) {
		PooledModel bufferedModel = new PooledModel(model, vertices);
		vertices += model.vertexCount();
		models.add(bufferedModel);
		pendingUpload.add(bufferedModel);

		setDirty();
		return bufferedModel;
	}

	public void flush() {
		if (dirty) {
			if (anyToRemove) processDeletions();

			vbo.bind();
			if (realloc()) {
				uploadAll();
			} else {
				uploadPending();
			}
			vbo.unbind();

			dirty = false;
			pendingUpload.clear();
		}
	}

	private void processDeletions() {

		// remove deleted models
		models.removeIf(PooledModel::isDeleted);

		// re-evaluate first vertex for each model
		int vertices = 0;
		for (PooledModel model : models) {
			if (model.first != vertices)
				pendingUpload.add(model);

			model.first = vertices;

			vertices += model.getVertexCount();
		}

		this.vertices = vertices;
		this.anyToRemove = false;
	}

	/**
	 * Assumes vbo is bound.
	 *
	 * @return true if the buffer was reallocated
	 */
	private boolean realloc() {
		int neededSize = vertices * format.getStride();
		if (neededSize > bufferSize) {
			bufferSize = neededSize + 128;
			vbo.alloc(bufferSize);

			return true;
		}

		return false;
	}

	private void uploadAll() {
		MappedBuffer buffer = vbo.getBuffer(0, bufferSize);

		for (PooledModel model : models) {
			model.model.buffer(buffer);
			if (model.callback != null)
				model.callback.invoke(model);
		}

		buffer.flush();
	}

	private void uploadPending() {
		MappedBuffer buffer = vbo.getBuffer(0, bufferSize);

		int stride = format.getStride();
		for (PooledModel model : pendingUpload) {
			int pos = model.first * stride;
			buffer.position(pos);
			model.model.buffer(buffer);
			if (model.callback != null)
				model.callback.invoke(model);
		}
		pendingUpload.clear();

		buffer.flush();
	}

	private void setDirty() {
		dirty = true;
	}

	public void delete() {
		vbo.delete();
	}

	public class PooledModel implements IBufferedModel {

		private final ElementBuffer ebo;
		private Callback callback;

		private final IModel model;
		private int first;

		private boolean remove;

		public PooledModel(IModel model, int first) {
			this.model = model;
			this.first = first;
			ebo = model.createEBO();
		}

		@Override
		public VertexFormat getFormat() {
			return model.format();
		}

		@Override
		public int getVertexCount() {
			return model.vertexCount();
		}

		@Override
		public void setupState() {
			vbo.bind();
			ebo.bind();
			AttribUtil.enableArrays(getAttributeCount());
			getFormat().vertexAttribPointers(0);
		}

		@Override
		public void clearState() {
			AttribUtil.disableArrays(getAttributeCount());
			ebo.unbind();
			vbo.unbind();
		}

		@Override
		public void drawCall() {
			Backend.getInstance().compat.baseVertex.drawElementsBaseVertex(GlPrimitive.TRIANGLES, ebo.elementCount, ebo.eboIndexType, 0, first);
		}

		@Override
		public void drawInstances(int instanceCount) {
			if (!valid()) return;

			ebo.bind();

			Backend.log.info(StringUtil.args("drawElementsInstancedBaseVertex", GlPrimitive.TRIANGLES, ebo.elementCount, ebo.eboIndexType, 0, instanceCount, first));

			Backend.getInstance().compat.baseVertex.drawElementsInstancedBaseVertex(GlPrimitive.TRIANGLES, ebo.elementCount, ebo.eboIndexType, 0, instanceCount, first);
		}

		@Override
		public boolean isDeleted() {
			return false;
		}

		@Override
		public void delete() {
			setDirty();
			anyToRemove = true;
			remove = true;
		}

		public PooledModel setReallocCallback(Callback callback) {
			this.callback = callback;
			return this;
		}
	}

	@FunctionalInterface
	public interface Callback {
		void invoke(PooledModel arenaModel);
	}
}
