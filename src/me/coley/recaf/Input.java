package me.coley.recaf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import javafx.application.Platform;
import me.coley.event.Bus;
import me.coley.event.Listener;
import me.coley.recaf.bytecode.Asm;
import me.coley.recaf.event.RequestAgentSaveEvent;
import me.coley.recaf.event.AgentSaveEvent;
import me.coley.recaf.event.ClassDirtyEvent;
import me.coley.recaf.event.ClassLoadInstrumentedEvent;
import me.coley.recaf.event.RequestExportEvent;
import me.coley.recaf.event.ExportEvent;
import me.coley.recaf.event.NewInputEvent;
import me.coley.recaf.event.RequestSaveStateEvent;
import me.coley.recaf.event.ResourceUpdateEvent;
import me.coley.recaf.event.SaveStateEvent;
import me.coley.recaf.util.Streams;

/**
 * 
 * @author Matt
 */
public class Input {
	private static final int HISTORY_LENGTH = 10;
	private static final String HIST_POSTFIX = ".hst";
	/**
	 * The file loaded from.
	 */
	public final File input;
	/**
	 * Instrumentation instance loaded from.
	 */
	private final Instrumentation instrumentation;
	/**
	 * Map of class names to ClassNode representations of the classes.
	 */
	public final Set<String> classes = new HashSet<>();;
	/**
	 * Set of classes to be updated in the next save-state.
	 */
	private final Set<String> dirtyClasses = new HashSet<>();
	/**
	 * Map of resource names to their raw bytes.
	 */
	public final Set<String> resources = new HashSet<>();;
	/**
	 * File system representation of contents of input.
	 */
	private final FileSystem system;
	/**
	 * Map of class names to ClassNodes.
	 */
	private final FileMap<String, ClassNode> proxyClasses;
	/**
	 * Map of resource names to their value.
	 */
	private final FileMap<String, byte[]> proxyResources;
	/**
	 * History manager of changes.
	 */
	private final Map<String, History> history = new HashMap<>();

	public Input(Instrumentation instrumentation) throws IOException {
		this.input = null;
		this.instrumentation = instrumentation;
		system = createSystem(instrumentation);
		Bus.INSTANCE.subscribe(this);
		proxyClasses = createClassMap();
		proxyResources = createResourceMap();
		Bus.INSTANCE.subscribe(proxyClasses);
		Bus.INSTANCE.subscribe(proxyResources);
		current = this;
	}

	public Input(File input) throws IOException {
		this.input = input;
		this.instrumentation = null;
		if (input.getName().endsWith(".class")) {
			system = createSystem(readClass());
		} else {
			system = createSystem(readArchive());
		}
		Bus.INSTANCE.subscribe(this);
		proxyClasses = createClassMap();
		proxyResources = createResourceMap();
		Bus.INSTANCE.subscribe(proxyClasses);
		Bus.INSTANCE.subscribe(proxyResources);
		current = this;
	}

	@Listener
	private void onNewInput(NewInputEvent input) {
		// New input is loaded.
		// Don't want events still coming around here.
		if (input.get() != this) {
			// Run-later so event system doesn't
			// concurrent-modification-exception
			Platform.runLater(() -> {
				Bus.INSTANCE.unsubscribe(this);
				Bus.INSTANCE.unsubscribe(proxyClasses);
				Bus.INSTANCE.unsubscribe(proxyResources);
			});
		}
	}

	/**
	 * Fires back a SaveStateEvent.
	 */
	@Listener
	private void onSaveRequested(RequestSaveStateEvent event) {
		Bus.INSTANCE.post(new SaveStateEvent(dirtyClasses));
		dirtyClasses.clear();
	}

	/**
	 * Marks a class as dirty <i>(To be saved in next save-state)</i>.
	 */
	@Listener
	private void onClassMarkedDirty(ClassDirtyEvent event) {
		String name = event.getNode().name;
		dirtyClasses.add(name);
	}

	/**
	 * Creates a backup of classes in the SaveStateEvent.
	 */
	@Listener
	private void onSave(SaveStateEvent event) {
		for (String name : event.getClasses()) {
			// ensure history for item exists
			if (!history.containsKey(name)) {
				history.put(name, new History(name));
			}
			// Update history.
			// This will in turn update the current stored class instance.
			try {
				byte[] modified = Asm.getBytes(proxyClasses.get(name));
				History classHistory = history.get(name);
				classHistory.update(modified);
				Logging.info("Save state created for: " + name + " [" + classHistory.length + " total states]");
			} catch (Exception e) {
				Logging.error(e);
			}
		}
	}

	/**
	 * Export contents to file given by the event.
	 * 
	 * @throws IOException
	 *             Thrown if the file could not be written to, or if a file
	 *             could not be exported from the virtual file system.
	 */
	@Listener
	private void onExportRequested(RequestExportEvent event) throws IOException {
		Map<String, byte[]> contents = new HashMap<>();
		Logging.info("Exporting to " + event.getFile().getAbsolutePath());
		// write classes
		Logging.info("Writing " + classes.size() + " classes...");
		int modified = 0;
		for (String name : classes) {
			// Export if file has been modified.
			// We know if it is modified if it has a history or is marked as
			// dirty.
			if (dirtyClasses.contains(name) || history.containsKey(name)) {
				byte[] data = Asm.getBytes(getClass(name));
				contents.put(name + ".class", data);
				modified++;
			} else {
				// Otherwise we don't even have to have ASM regenerate the
				// bytecode. We can just plop the exact original file back
				// into the output.
				// This is great for editing one file in a large system.
				byte[] data = getFile(getPath(name));
				contents.put(name + ".class", data);
			}
		}
		Logging.info(modified + " modified files", 1);
		Logging.info("Writing " + resources.size() + " resources...");
		// Write resources. Can't modify these yet so just take them directly
		// from the system.
		for (String name : resources) {
			byte[] data = getFile(getPath(name));
			contents.put(name, data);
		}
		// Post to event bus. Allow plugins to inject their own files to the
		// output.
		Bus.INSTANCE.post(new ExportEvent(event.getFile(), contents));
		// Save contents to jar.
		try (JarOutputStream output = new JarOutputStream(new FileOutputStream(event.getFile()))) {
			for (Entry<String, byte[]> entry : contents.entrySet()) {
				output.putNextEntry(new JarEntry(entry.getKey()));
				output.write(entry.getValue());
				output.closeEntry();
			}
		}
		Logging.info("Exported to: " + event.getFile());

	}

	/**
	 * Populates class and resource maps.
	 * 
	 * @throws IOException
	 *             Thrown if the archive could not be read, or an internal file
	 *             could not be read.
	 */
	private Map<String, byte[]> readArchive() throws IOException {
		Map<String, byte[]> contents = new HashMap<>();
		try (ZipFile file = new ZipFile(input)) {
			// iterate zip entries
			Enumeration<? extends ZipEntry> entries = file.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				// skip directories
				if (entry.isDirectory()) continue;
				try (InputStream is = file.getInputStream(entry)) {
					// add as class, or resource if not a class file.
					String name = entry.getName();
					if (name.endsWith(".class")) {
						addClass(contents, name, is);
					} else {
						addResource(contents, name, is);
					}
				}
			}
		}
		return contents;
	}

	/**
	 * Populates class map.
	 * 
	 * @throws IOException
	 *             Thrown if the file could not be read.
	 */
	private Map<String, byte[]> readClass() throws IOException {
		Map<String, byte[]> contents = new HashMap<>();
		try (InputStream is = new FileInputStream(input)) {
			addClass(contents, input.getName(), is);
		}
		return contents;
	}

	/**
	 * Try to add the class contained in the given stream to the classes map.
	 * 
	 * @param name
	 *            Entry name.
	 * @param is
	 *            Stream of entry.
	 * @throws IOException
	 *             Thrown if stream could not be read or if ClassNode could not
	 *             be derived from the streamed content.
	 */
	private void addClass(Map<String, byte[]> contents, String name, InputStream is) throws IOException {
		byte[] value = Streams.from(is);
		try {
			String className = new ClassReader(value).getClassName();
			classes.add(className);
			contents.put(className, value);
		} catch (Exception e) {
			Logging.warn(String.format("Could not read archive entry: '%s' as a class. Added as resource instead.", name));
			contents.put(name, value);
		}
	}

	/**
	 * Try to add the resource contained in the given stream to the resource
	 * map.
	 * 
	 * @param name
	 *            Entry name.
	 * @param is
	 *            Stream of entry.
	 * @throws IOException
	 *             Thrown if stream could not be read.
	 */
	private void addResource(Map<String, byte[]> contents, String name, InputStream is) throws IOException {
		resources.add(name);
		contents.put(name, Streams.from(is));
	}

	/**
	 * @return Map of ClassNodes.
	 */
	public Map<String, ClassNode> getClasses() {
		return proxyClasses;
	}

	/**
	 * Get a ClassNode by its name.
	 * 
	 * @param name
	 *            Name of class.
	 * @return ClassNode if found, {@code null} otherwise.
	 */
	public ClassNode getClass(String name) {
		return proxyClasses.get(name);
	}

	/**
	 * @return Map of resources.
	 */
	public Map<String, byte[]> getResources() {
		return proxyResources;
	}

	/**
	 * Get a resource by its name.
	 * 
	 * @param name
	 *            Name of resource.
	 * @return {@code byte[]} if found, {@code null} otherwise.
	 */
	public byte[] getResource(String name) {
		return proxyResources.get(name);
	}

	/**
	 * @return Map of save states for classes.
	 */
	public Map<String, History> getHistory() {
		return history;
	}

	/**
	 * Undo the last action for the ClassNode with the given name. To receive
	 * changes re-use {@link #getClass(String)}.
	 * 
	 * @param name
	 * @throws IOException
	 */
	public void undo(String name) throws IOException {
		History hist = history.get(name);
		byte[] last = hist.pop();
		if (last != null) {
			proxyClasses.removeCache(name);
			write(getPath(name), last);
			Logging.info("Reverted '" + name + "'");
		} else {
			Logging.info("No history for '" + name + "' to revert back to");
		}
	}

	/**
	 * Generate FileSystem to represent contained data:
	 * <ul>
	 * <li>{@link #classes}</li>
	 * <li>{@link #resources}</li>
	 * </ul>
	 * 
	 * @return FileSystem representation of input.
	 * @throws IOException
	 */
	private FileSystem createSystem(Map<String, byte[]> contents) throws IOException {
		FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		for (Entry<String, byte[]> entry : contents.entrySet()) {
			Path path = fs.getPath("/" + entry.getKey());
			Files.createDirectories(path.getParent());
			write(path, entry.getValue());
		}
		return fs;
	}

	/**
	 * Generate FileSystem to represent classes loaded in the given
	 * Instrumentation instance.
	 * 
	 * @param instrumentation
	 * @return FileSystem representation of instrumentation.
	 * @throws IOException
	 */
	private FileSystem createSystem(Instrumentation instrumentation) throws IOException {
		FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		// Wrapped return of "readContents" in another map to prevent
		// ConcurrentModificationException for when a new class is loaded
		// while this loop is executing.
		Map<String, byte[]> contents = new HashMap<>(readContents(instrumentation));
		for (Entry<String, byte[]> entry : contents.entrySet()) {
			Path path = fs.getPath("/" + entry.getKey());
			Files.createDirectories(path.getParent());
			write(path, entry.getValue());
		}
		return fs;
	}

	/**
	 * Read bytecode of classes in the instrumented environment.
	 * 
	 * @param instrumentation
	 * @return Map of runtime classes's bytecode.
	 * @throws IOException
	 */
	private Map<String, byte[]> readContents(Instrumentation instrumentation) throws IOException {
		Map<String, byte[]> map = new HashMap<>();
		// add all existing classes
		for (Class<?> c : instrumentation.getAllLoadedClasses()) {
			String name = c.getName().replace(".", "/");
			String path = name + ".class";
			ClassLoader loader = c.getClassLoader();
			if (loader == null) {
				loader = ClassLoader.getSystemClassLoader();
			}
			InputStream is = loader.getResourceAsStream(path);
			if (is != null) {
				classes.add(name);
				map.put(name, Streams.from(is));
			}
		}
		return map;
	}

	/**
	 * Called after the window is loaded. This allows the UI to register an
	 * instance of "Input" so that it can use it when fetching values posted by
	 * the transformer in this method.
	 */
	public void registerLoadListener() {
		// register transformer so new classes can be added on the fly
		instrumentation.addTransformer((loader, className, classBeingRedefined, protectionDomain, classfileBuffer) -> {
			// skip invalid entries
			if (className == null || classfileBuffer == null) {
				return classfileBuffer;
			}
			// add classes as they're loaded
			try {
				instLoaded(className, classfileBuffer);
			} catch (IOException e) {
				Logging.warn("Failed to load inst. class: " + className);
			}
			return classfileBuffer;
		}, true);
	}

	@Listener
	private void onAgentSave(RequestAgentSaveEvent event) {
		Map<String, byte[]> targets = new HashMap<>();
		Logging.info("Redefining classes...");
		// write classes
		int modified = 0;
		for (String name : classes) {
			// Export if file has been modified.
			// We know if it is modified if it has a history or is marked as
			// dirty.
			if (dirtyClasses.contains(name)) {
				byte[] data = Asm.getBytes(getClass(name));
				targets.put(name, data);
				modified++;
			}
		}
		Logging.info(modified + " modified files", 1);
		// Post to event bus. Allow plugins to inject their own files to the
		// output.
		Bus.INSTANCE.post(new AgentSaveEvent(instrumentation, targets));
		// Create new definitions and apply
		try {
			int i = 0;
			ClassDefinition[] defs = new ClassDefinition[targets.size()];
			for (Entry<String, byte[]> entry : targets.entrySet()) {
				String name = entry.getKey().replace("/", ".");
				ClassLoader loader = ClassLoader.getSystemClassLoader();
				defs[i] = new ClassDefinition(Class.forName(name, false, loader), entry.getValue());
				i++;
			}
			instrumentation.redefineClasses(defs);
			Logging.info("Redefinition complete.");
		} catch (Exception e) {
			Logging.error(e);
		}
		// clear dirty list
		dirtyClasses.clear();
	}

	/**
	 * When a class is loaded via the transformer, add it to the file-system and
	 * class list.
	 * 
	 * @param className
	 * @param classfileBuffer
	 * @throws IOException
	 */
	private void instLoaded(String className, byte[] classfileBuffer) throws IOException {
		// add to file system
		Path path = getFileSystem().getPath("/" + className);
		Files.createDirectories(path.getParent());
		write(path, classfileBuffer);
		// add to class list
		classes.add(className);
		// send notification
		Bus.INSTANCE.post(new ClassLoadInstrumentedEvent(className));
	}

	/**
	 * Wrapper for system.getPath().
	 * 
	 * @param name
	 *            File name.
	 * @return File path.
	 */
	private Path getPath(String name) {
		return system.getPath("/" + name);
	}

	/**
	 * @return Files contained within input as navigable file system.
	 */
	public FileSystem getFileSystem() {
		return system;
	}

	/**
	 * Retrieve bytes of file in the {@link #getFileSystem() virtual system}.
	 * 
	 * @param name
	 *            File name. For classes, use internal names.
	 * @return Bytes of requested files.
	 * @throws IOException
	 *             File could not be read from.
	 */
	public byte[] getFile(String name) throws IOException {
		return Files.readAllBytes(system.getPath("/" + name));
	}

	/**
	 * Retrieve bytes of file in the {@link #getFileSystem() virtual system}.
	 * 
	 * @param path
	 *            File path.
	 * @return Bytes of requested files.
	 * @throws IOException
	 *             File could not be read from.
	 */
	private static byte[] getFile(Path path) throws IOException {
		return Files.readAllBytes(path);
	}

	/**
	 * Removes file by name from local system.
	 * 
	 * @param name
	 *            File name.
	 * @throws IOException
	 *             Thrown if the file could not be removed.
	 */
	private void removeFile(String name) throws IOException {
		Files.delete(getPath(name));
	}

	/**
	 * Add file to local system.
	 * 
	 * @param path
	 *            Path to write to.
	 * @param value
	 *            Bytes to write.
	 * @throws IOException
	 *             Thrown if could not write to path.
	 */
	private static void write(Path path, byte[] value) throws IOException {
		Files.write(path, value, StandardOpenOption.CREATE);
	}

	/**
	 * @return FileMap of classes.
	 */
	private FileMap<String, ClassNode> createClassMap() {
		return new FileMap<String, ClassNode>(classes) {
			@Listener
			private void onClassMarkedDirty(ClassDirtyEvent event) {
				// TODO: If a person renames a class, move the value in the map
				// to the proper location. Maybe make a dedicated class rename
				// event?
				// Also when doing this, keep track of what classes reference
				// the renamed class.
				// Then when you do the rename again, its quicker since less
				// files need updating.
				// When a class is marked dirty, rescan for references.
			}

			@Override
			ClassNode castValue(byte[] file) {
				try {
					return Asm.getNode(file);
				} catch (IOException e) {
					Logging.fatal(e);
				}
				return null;
			}

			@Override
			byte[] castBytes(ClassNode value) {
				return Asm.getBytes(value);
			}

			@Override
			String castKey(Object in) {
				return in.toString();
			}
		};
	}

	/**
	 * @return FileMap of resources.
	 */
	private FileMap<String, byte[]> createResourceMap() {
		return new FileMap<String, byte[]>(resources) {
			@Listener
			private void onUpdate(ResourceUpdateEvent event) {
				if (event.getResource() == null) {
					remove(event.getResourceName());
				} else {
					cache.remove(event.getResourceName());
				}
			}

			@Override
			byte[] castValue(byte[] file) {
				return file;
			}

			@Override
			byte[] castBytes(byte[] value) {
				return value;
			}

			@Override
			String castKey(Object in) {
				return in.toString();
			}
		};
	}

	/**
	 * History manager for files.
	 * 
	 * @author Matt
	 */
	public class History {
		/**
		 * File being tracked.
		 */
		public final String name;
		/**
		 * Number of elements.
		 */
		public int length;

		public History(String name) {
			this.name = name;
		}

		public void clear() throws IOException {
			for (int i = 0; i < length; i++) {
				Path cur = getPath(name + HIST_POSTFIX + i);
				Files.delete(cur);
			}
			length = 0;
		}

		/**
		 * Gets most recent change, shifts all history up.
		 * 
		 * @return Last value before change.
		 * @throws IOException
		 *             Thrown if the history file could not be read, or if
		 *             history files could not be re-arranged.
		 */
		public byte[] pop() throws IOException {
			// Path of most recent element
			Path lastPath = getPath(name + HIST_POSTFIX + "0");
			if (Files.exists(lastPath)) {
				byte[] last = getFile(lastPath);
				// Move histories up.
				// Acts like a history stack.
				for (int i = -1; i < length; i++) {
					Path cur = i == -1 ? getPath(name) : getPath(name + HIST_POSTFIX + i);
					Path newer = getPath(name + HIST_POSTFIX + (i + 1));
					if (Files.exists(newer)) {
						Files.move(newer, cur, StandardCopyOption.REPLACE_EXISTING);
					}
				}
				// Decrease history count, return last
				length--;
				return last;
			}
			// No history to be found
			return null;
		}

		/**
		 * Updates current value, pushing the latest value into the history
		 * stack.
		 * 
		 * @param modified
		 *            Changed value.
		 * @throws IOException
		 *             thrown if the value failed to push onto the stack.
		 */
		public void update(byte[] modified) throws IOException {
			Path path = getPath(name);
			for (int i = Math.min(length, HISTORY_LENGTH) + 1; i > 0; i--) {
				Path cur = getPath(name + HIST_POSTFIX + i);
				Path newer = getPath(name + HIST_POSTFIX + (i - 1));
				// start of history
				if (Files.exists(newer)) {
					Files.copy(newer, cur, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			// Update lastest history element.
			Path pathNewHistory = getPath(name + HIST_POSTFIX + "0");
			write(pathNewHistory, getFile(name));
			// Update current value.
			write(path, modified);
			length++;
			if (length > HISTORY_LENGTH) {
				length = HISTORY_LENGTH;
			}
		}
	}

	/**
	 * Map wrapper for this structure. Allows users to access classes in a
	 * familiar fashion.
	 * 
	 * @author Matt
	 *
	 * @param <K>
	 * @param <V>
	 */
	public abstract class FileMap<K, V> implements Map<K, V> {
		protected Map<K, V> cache = new HashMap<>();
		protected final Set<K> keys;

		public FileMap(Set<K> keys) {
			this.keys = keys;
		}

		/**
		 * Exists because casting generics alone is a bad idea without
		 * implementation information.
		 * 
		 * @param in
		 *            Key input.
		 * @return Key output.
		 */
		abstract K castKey(Object in);

		/**
		 * Exists because casting generics alone is a bad idea without
		 * implementation information.
		 * 
		 * @param file
		 *            Value input.
		 * @return Value output.
		 */
		abstract V castValue(byte[] file);

		/**
		 * Exists because casting generics alone is a bad idea without
		 * implementation information.
		 * 
		 * @param value
		 *            Value input.
		 * @return {@code byte[]} of input.
		 */
		abstract byte[] castBytes(V value);

		@Override
		public int size() {
			return keys.size();
		}

		@Override
		public boolean isEmpty() {
			return keys.isEmpty();
		}

		@Override
		public boolean containsKey(Object key) {
			return keys.contains(key);
		}

		public boolean containsCache(Object key) {
			return cache.containsKey(key);
		}

		@Override
		public boolean containsValue(Object value) {
			throw new UnsupportedOperationException();
		}

		public void removeCache(Object key) {
			cache.remove(key);
		}

		@Override
		public V get(Object key) {
			// check if cached copy exists.
			V v = cache.get(key);
			if (v != null) {
				return v;
			}
			// no cache, fetch from file system, add to cache.
			try {
				v = castValue(getFile(key.toString()));
				cache.put(castKey(key), v);
				return v;
			} catch (ClosedByInterruptException e) {
				// happens when closing the editor window when runnon on an
				// instrumented input.
				// Ignore.
			} catch (IOException e) {
				Logging.warn(e);
			}
			return null;
		}

		@Override
		public V put(K key, V value) {
			keys.add(key);
			cache.remove(key);
			try {
				write(getPath(key.toString()), castBytes(value));
			} catch (IOException e) {
				Logging.fatal(e);
			}
			return value;
		}

		@Override
		public V remove(Object key) {
			String ks = key.toString();
			V v = get(key);
			try {
				removeFile(ks);
				keys.remove(ks);
				history.remove(ks);
			} catch (IOException e) {
				return v;
			}
			return v;
		}

		@Override
		public void putAll(Map<? extends K, ? extends V> m) {
			for (Entry<? extends K, ? extends V> entry : m.entrySet()) {
				put(entry.getKey(), entry.getValue());
			}
		}

		@Override
		public void clear() {
			cache.clear();
			for (K key : keys) {
				remove(key);
				history.remove(key);
			}
		}

		@Override
		public Set<K> keySet() {
			return keys;
		}

		@Override
		public Collection<V> values() {
			Set<V> s = new HashSet<>();
			for (K key : keys) {
				s.add(get(key));
			}
			return s;
		}

		@Override
		public Set<Entry<K, V>> entrySet() {
			Set<Entry<K, V>> s = new HashSet<>();
			for (final K key : keys) {
				final V value = get(key);
				s.add(new Entry<K, V>() {
					@Override
					public K getKey() {
						return key;
					}

					@Override
					public V getValue() {
						return value;
					}

					@Override
					public V setValue(V value) {
						throw new UnsupportedOperationException();
					}
				});
			}
			return s;
		}
	}

	/**
	 * Current instance.
	 */
	private static Input current;

	public static Input get() {
		return current;
	}

	// Uncomment for debugging needs.
	/*
	 * private void print() try Files.walkFileTree(getPath("/"), ne
	 * SimpleFileVisitor<Path>() @Overrid public FileVisitResul visitFile(Path
	 * file, BasicFileAttribute attr) System.out.format(">%s ", file)
	 * System.out.println("(" attr.size() " bytes)") return
	 * FileVisitResult.CONTINUE @Overrid publi FileVisitResult
	 * postVisitDirectory(Path dir, IOExceptio exc) retur
	 * FileVisitResult.CONTINUE @Overrid public FileVisitResul
	 * visitFileFailed(Path file, IOExceptio exc) System.err.println(exc) return
	 * FileVisitResult.CONTINUE }) catch (IOException e) e.printStackTrace() }
	 */
}