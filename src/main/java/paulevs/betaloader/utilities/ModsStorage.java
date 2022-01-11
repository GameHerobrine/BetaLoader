package paulevs.betaloader.utilities;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.minecraft.client.Minecraft;
import net.minecraft.util.io.CompoundTag;
import net.minecraft.util.io.NBTIO;
import paulevs.betaloader.remapping.ModEntry;
import paulevs.betaloader.remapping.RemapUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ModsStorage {
	private static final String MINECRAFT_URL = "https://launcher.mojang.com/v1/objects/43db9b498cb67058d2e12d394e6507722e71bb45/client.jar";
	private static final String JAVASSIST_URL = "https://repo1.maven.org/maven2/org/javassist/javassist/3.28.0-GA/javassist-3.28.0-GA.jar";
	
	private static final File MODS_FOLDER = new File(FabricLoader.getInstance().getGameDir().toString(), "mods");
	private static final File CONVERTED_FOLDER = CacheStorage.getCacheFile("converted_mods");
	private static final File MINECRAFT = CacheStorage.getCacheFile("minecraft.jar");
	private static final File JAVASSIST = CacheStorage.getCacheFile("javassist.jar");
	private static final File MODS_DATA = CacheStorage.getCacheFile("mods.nbt");
	
	private static final List<ModEntry> PATCHED_MODS = new ArrayList<>();
	
	private static ClassLoader sideLoader;
	
	// TODO replace JarJar with tiny remapper additional mappings
	public static void process() {
		CONVERTED_FOLDER.mkdirs();
		MODS_FOLDER.mkdirs();
		
		if (!FileUtil.downloadFile(MINECRAFT, MINECRAFT_URL, "Minecraft Unmapped Client")) {
			System.out.println("Abort mod loading process!");
			return;
		}
		
		CompoundTag modsData = getModsDataTag(MODS_DATA);
		
		File[] modsInFolder = MODS_FOLDER.listFiles();
		List<ModEntry> addMods = new ArrayList<>();
		
		for (File mod: modsInFolder) {
			ModEntry entry = ModEntry.makeEntry(mod, CONVERTED_FOLDER);
			if (entry == null) {
				continue;
			}
			PATCHED_MODS.add(entry);
			if (entry.requireUpdate(modsData)) {
				addMods.add(entry);
			}
		}
		
		if (!addMods.isEmpty()) {
			saveTag(modsData, MODS_DATA);
			RemapUtil.remap(addMods, CONVERTED_FOLDER);
		}
	}
	
	// TODO remove javassist
	public static boolean loadJavassist() {
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return true;
		}
		
		if (!FileUtil.downloadFile(JAVASSIST, JAVASSIST_URL, "Javassist")) {
			return false;
		}
		
		try {
			ClassLoader loader = Minecraft.class.getClassLoader();
			Method method = loader.getClass().getDeclaredMethod("addURL", URL.class);
			method.setAccessible(true);
			method.invoke(loader, JAVASSIST.toURI().toURL());
			return true;
		}
		catch (MalformedURLException e) {
			e.printStackTrace();
		}
		catch (InvocationTargetException e) {
			e.printStackTrace();
		}
		catch (NoSuchMethodException e) {
			e.printStackTrace();
		}
		catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		
		return false;
	}
	
	/**
	 * Get converted mods with proper classpath.
	 * @return {@link List} of mod {@link ModEntry}.
	 */
	public static List<ModEntry> getMods() {
		return PATCHED_MODS;
	}
	
	public static ClassLoader getSideLoader(File modFile) {
		if (sideLoader != null) {
			try {
				Method method = sideLoader.getClass().getDeclaredMethod("addURL", URL.class);
				method.setAccessible(true);
				method.invoke(sideLoader, modFile.toURI().toURL());
			}
			catch (MalformedURLException e) {
				e.printStackTrace();
			}
			catch (InvocationTargetException e) {
				e.printStackTrace();
			}
			catch (NoSuchMethodException e) {
				e.printStackTrace();
			}
			catch (IllegalAccessException e) {
				e.printStackTrace();
			} return sideLoader;
		}
		
		List<Path> paths = new ArrayList<Path>();
		List<URL> urls = new ArrayList<>(FabricLauncherBase.getLauncher().getLoadTimeDependencies());
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			paths.add(FabricLoader.getInstance().getModContainer("betaloader").get().getRootPath());
		}
		else {
			try {
				File file = new File(ModsStorage.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				paths.add(file.toPath());
				file = new File(file.getParentFile().getParentFile(), ".fabric/remappedJars/minecraft-1.0.0-beta.7.3/intermediary-minecraft.jar");
				if (file.exists()) {
					paths.add(file.toPath());
				}
			}
			catch (URISyntaxException e) {
				e.printStackTrace();
			}
		}
		
		try {
			urls.add(modFile.toURI().toURL());
			for (Path path: paths) {
				urls.add(path.toUri().toURL());
			}
		}
		catch (MalformedURLException e) {
			e.printStackTrace();
		}
		
		sideLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]));
		return sideLoader;
	}
	
	private static CompoundTag getModsDataTag(File file) {
		CompoundTag tag = null;
		if (file.exists()) {
			try {
				FileInputStream stream = new FileInputStream(file);
				tag = NBTIO.readGzipped(stream);
				stream.close();
			}
			catch (IOException exception) {
				exception.printStackTrace();
			}
		}
		return tag == null ? new CompoundTag() : tag;
	}
	
	private static void saveTag(CompoundTag tag, File file) {
		file.getParentFile().mkdirs();
		try {
			FileOutputStream stream = new FileOutputStream(file);
			NBTIO.writeGzipped(tag, stream);
			stream.close();
		}
		catch (IOException exception) {
			exception.printStackTrace();
		}
	}
	
	private static Set<File> getFiles(File dir) {
		return Arrays.stream(dir.listFiles()).filter(file -> {
			if (file.isFile()) {
				String name = file.getName();
				return name.endsWith(".jar") || name.endsWith(".zip");
			}
			return false;
		}).collect(Collectors.toSet());
	}
}
