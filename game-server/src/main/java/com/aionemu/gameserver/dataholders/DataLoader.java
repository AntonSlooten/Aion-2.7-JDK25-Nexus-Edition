/*
 * This file is part of aion-emu <aion-emu.com>.
 * (License info retained)
 */
package com.aionemu.gameserver.dataholders;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset; // TAMBAHAN IMPORT
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.HiddenFileFilter;
import org.slf4j.LoggerFactory;

import org.slf4j.Logger;

abstract class DataLoader {

	protected Logger log = LoggerFactory.getLogger(getClass().getName());
	private static final String PATH = "./data/static_data/";
	private File dataFile;

	DataLoader(String file) {
		this.dataFile = new File(PATH + file);
	}

	protected void loadData() {
		if (dataFile.isDirectory()) {
			@SuppressWarnings("deprecation")
			Collection<?> files = FileUtils.listFiles(
				dataFile,
				FileFilterUtils.andFileFilter(FileFilterUtils.andFileFilter(
					FileFilterUtils.notFileFilter(FileFilterUtils.nameFileFilter("new")),
					FileFilterUtils.suffixFileFilter(".txt")), HiddenFileFilter.VISIBLE), HiddenFileFilter.VISIBLE);

			for (Object file1 : files) {
				File f = (File) file1;
				loadFile(f);
			}
		}
		else {
			loadFile(dataFile);
		}
	}

	private void loadFile(File file) {
		// PERBAIKAN: Menggunakan try-with-resources dan menambahkan Charset
		try (LineIterator it = FileUtils.lineIterator(file, Charset.defaultCharset().name())) {
			while (it.hasNext()) {
				String line = it.nextLine();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				parse(line);
			}
		}
		catch (IOException e) {
			log.error("Error while loading " + getClass().getSimpleName() + ", file: " + file.getPath(), e);
		}
		// Blok finally { LineIterator.closeQuietly(it); } DIHAPUS karena try-with-resources sudah otomatis menutupnya.
	}

	protected abstract void parse(String dataEntry);

	public boolean saveData() {
		String desc = PATH + getSaveFile();
		log.info("Saving " + desc);

		try (FileWriter fr = new FileWriter(desc)) { // PERBAIKAN: try-with-resources
			saveEntries(fr);
			fr.flush();
			return true;
		}
		catch (Exception e) {
			log.error("Error while saving " + desc, e);
			return false;
		}
	}

	protected abstract String getSaveFile();

	protected void saveEntries(FileWriter fileWriter) throws Exception {
	}
}