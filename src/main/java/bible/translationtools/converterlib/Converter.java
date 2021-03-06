package bible.translationtools.converterlib;

import bible.translationtools.recorderapp.wav.WavFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import bible.translationtools.recorderapp.wav.WavCue;
import bible.translationtools.recorderapp.wav.WavMetadata;
import bible.translationtools.recorderapp.filespage.FileNameExtractor;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * This class is to convert takes from old version of "Recorder"
 * to new. Provide -d parameter with a directory
 * that has audio files
 */
public class Converter implements IConverter {

    private List<Project> projects = new ArrayList<>();

    Scanner reader = new Scanner(System.in);
    String rootPath;
    String archivePath;
    File rootDir;
    File archiveDir;
    File dateTimeDir;
    boolean backupCreated;

    public Converter(String rootPath) throws Exception {
        this.rootPath = rootPath;
        this.archivePath = rootPath + "Archive";

        this.rootDir = new File(this.rootPath);
        this.archiveDir = new File(this.archivePath);

        this.setDateTimeDir();
    }

    @Override
    public Integer execute() {
        this.createBackup();

        if(!this.backupCreated) return -1;

        int counter = 0;

        for(Project p: projects) {
            if(p.pending) {
                File projectDir = new File(Utils.strJoin(new String[] {
                        this.rootDir.getAbsolutePath(),
                        p.language,
                        p.version,
                        p.book
                }, File.separator));

                Collection<File> takes = FileUtils.listFiles(projectDir, null, true);
                for (File take: takes) {
                    if((FilenameUtils.getExtension(take.getName()).equals("wav") ||
                            FilenameUtils.getExtension(take.getName()).equals("WAV")) &&
                            !take.getName().equals("chapter.wav")) {

                        String mode = p.mode;

                        WavFile wf = new WavFile(take);
                        WavMetadata wmd = wf.getMetadata();
                        FileNameExtractor fne = new FileNameExtractor(take);

                        if(fne.matched())
                        {
                            this.updateMetadata(wmd, fne, mode);
                            wf.commit();

                            // Rename file if it was created prior to version.8.5
                            if(fne.version84())
                            {
                                String newName = take.getParent() + File.separator;
                                newName += wmd.getLanguage()
                                        + "_" + wmd.getVersion()
                                        + "_b" + wmd.getBookNumber()
                                        + "_" + wmd.getSlug()
                                        + "_c" + wmd.getChapter()
                                        + "_v" + wmd.getStartVerse()
                                        + (mode.equals("chunk") ? "-" + wmd.getEndVerse() : "")
                                        + "_t" + String.format("%02d", fne.getTake())
                                        + ".wav";

                                File newFile = new File(newName);
                                take.renameTo(newFile);
                            }

                            counter++;
                        }

                        System.out.println(take.getName());
                    }
                }
                p.pending = false;
            }
        }

        System.out.println("Conversion complete: " + counter + " files have been affected.");
        return counter;
    }

    @Override
    public void analyze()
    {
        if(!this.rootDir.exists()) return;

        Collection<File> takes = FileUtils.listFiles(this.rootDir, null, true);
        for (File take: takes) {
            if((FilenameUtils.getExtension(take.getName()).equals("wav") ||
                    FilenameUtils.getExtension(take.getName()).equals("WAV")) &&
                    !take.getName().equals("chapter.wav")) {

                String[] parts = take.getName().split("_");
                String lang = parts.length > 0 ? parts[0] : "";
                String version = parts.length > 1 ? parts[1] : "";
                String book = parts.length > 2
                        ? (parts[2].startsWith("b") && parts.length > 3
                        ? parts[3] : parts[2]) : "";

                if (!lang.isEmpty() && !version.isEmpty() && !book.isEmpty()) {
                    if (this.getProject(lang, version, book) == null) {
                        String mode = this.detectMode(take);
                        boolean shouldUpdate = this.hasBadMetadata(take);
                        this.projects.add(new Project(mode, lang, version, book, shouldUpdate));
                    }
                }
            }
        }
    }

    @Override
    public void getModeFromUser() {
        for(Project p: this.projects) {
            Boolean modeSet = false;
            while (!modeSet) {
                System.out.println("Select mode for \"" + p + "\". " +
                        (!p.mode.isEmpty() ? "Current mode: " + p.mode : ""));
                System.out.println("(1 - verse, 2 - chunk): ");
                int input = this.reader.nextInt();
                String previousMode = p.mode;
                p.mode = input == 1 ? "verse" : (input == 2 ? "chunk" : "");
                if(!p.mode.equals(previousMode)) {
                    p.pending = true;
                }

                if(!p.mode.isEmpty()) modeSet = true;
            }
        }

        this.reader.close();
    }

    @Override
    public List<Project> getProjects() {
        return this.projects;
    }

    @Override
    public void setProjects(List<Project> projects) {
        this.projects = projects;
    }

    @Override
    public void setDateTimeDir() {
        String dt = Utils.getDateTimeStr();
        this.dateTimeDir = new File(this.archiveDir + File.separator + dt);
    }

    private void createBackup() {
        this.backupCreated = false;

        // Create Archive folder if needed
        if(!this.archiveDir.exists())
        {
            this.archiveDir.mkdir();
        }

        // Create DateTime folder
        if(!this.dateTimeDir.exists())
        {
            this.dateTimeDir.mkdir();
        }

        if(this.rootDir.exists())
        {
            try {
                for (Project p : projects) {
                    if (p.pending) {
                        File projectDir = new File(Utils.strJoin(new String[] {
                                this.rootDir.getAbsolutePath(),
                                p.language,
                                p.version,
                                p.book
                        }, File.separator));

                        File projectDirArchive = new File(Utils.strJoin(new String[] {
                                this.dateTimeDir.getAbsolutePath(),
                                p.language,
                                p.version,
                                p.book
                        }, File.separator));


                        for(File child: projectDir.listFiles()) {
                            if(child.isDirectory())
                            {
                                FileUtils.copyDirectoryToDirectory(child, projectDirArchive);
                            }
                            else
                            {
                                FileUtils.copyFileToDirectory(child, projectDirArchive);
                            }
                        }
                    }
                }
                this.backupCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void updateMetadata(WavMetadata wmd, FileNameExtractor fne, String mode)
    {
        BookParser bp = new BookParser();

        if(wmd.getLanguage().isEmpty())
        {
            wmd.setLanguage(fne.getLang());
        }
        if(wmd.getAnthology().isEmpty())
        {
            String ant = bp.GetAnthology(fne.getBook());
            wmd.setAnthology(ant);
        }
        if(wmd.getVersion().isEmpty())
        {
            wmd.setVersion(fne.getSource());
        }
        if(wmd.getSlug().isEmpty())
        {
            wmd.setSlug(fne.getBook());
        }
        if(wmd.getBookNumber().isEmpty())
        {
            int bn = fne.getBookNumber();
            if(bn <= 0)
            {
                bn = bp.GetBookNumber(fne.getBook());
            }

            String bnStr = FileNameExtractor.unitIntToString(bn);
            wmd.setBookNumber(bnStr);
        }
        if(wmd.getChapter().isEmpty())
        {
            int cn = fne.getChapter();
            String cnStr = FileNameExtractor.unitIntToString(cn);
            wmd.setChapter(cnStr);
        }

        // Set mode every time
        wmd.setModeSlug(mode);

        if(wmd.getStartVerse().isEmpty())
        {
            int sv = fne.getStartVerse();
            String svStr = FileNameExtractor.unitIntToString(sv);
            wmd.setStartVerse(svStr);
        }

        // Set endVerse every time
        int ev = fne.getEndVerse();
        if(ev == -1)
        {
            if(mode.equals("chunk"))
            {
                String ant  = bp.GetAnthology(fne.getBook());
                String path = "assets/chunks/" + ant + "/" + fne.getBook() + "/chunks.json";

                String cnStr = FileNameExtractor.unitIntToString(fne.getChapter());
                String svStr = FileNameExtractor.unitIntToString(fne.getStartVerse());
                String id = cnStr + "-" + svStr;

                ChunksParser chp = new ChunksParser(path);
                ev = chp.GetLastVerse(id);
            }
            else
            {
                ev = Integer.parseInt(wmd.getStartVerse());
            }
        }

        String evStr = FileNameExtractor.unitIntToString(ev);
        wmd.setEndVerse(evStr);

        // Update verse markers <stikethrough>if mode is "verse"<stikethrough>
        if(/*mode == "verse" && */wmd.getCuePoints().isEmpty()) {
            int startv = Integer.parseInt(wmd.getStartVerse());
            wmd.addCue(new WavCue(String.valueOf(startv), 0));
        }
    }

    private String detectMode(File file)
    {
        String mode = "";

        if(!file.isDirectory() && !file.getName().equals("chapter.wav")) {
            WavFile wf = new WavFile(file);
            WavMetadata wmd = wf.getMetadata();

            if(!wmd.getModeSlug().isEmpty())
            {
                mode = wmd.getModeSlug();
                return mode;
            }
        }

        return mode;
    }

    private boolean hasBadMetadata(File file) {
        if(!file.isDirectory() && !file.getName().equals("chapter.wav")) {
            WavFile wf = new WavFile(file);
            WavMetadata wmd = wf.getMetadata();

            if(wmd.getLanguage().isEmpty()) {
                return true;
            }

            if(wmd.getAnthology().isEmpty()) {
                return true;
            }

            if(wmd.getVersion().isEmpty()) {
                return true;
            }

            if(wmd.getSlug().isEmpty()) {
                return true;
            }

            if(wmd.getBookNumber().isEmpty()) {
                return true;
            }

            if(wmd.getModeSlug().isEmpty()) {
                return true;
            }

            if(wmd.getChapter().isEmpty()) {
                return true;
            }

            if(wmd.getStartVerse().isEmpty()) {
                return true;
            }

            if(wmd.getEndVerse().isEmpty()) {
                return true;
            }

            if(wmd.getCuePoints().isEmpty()) {
                return true;
            }
        } else {
            return true;
        }

        return false;
    }

    private Project getProject(String language, String version, String book) {
        for (Project p: this.projects) {
            if (p.language.equals(language) && p.version.equals(version) && p.book.equals(book)) {
                return p;
            }
        }

        return null;
    }
}
