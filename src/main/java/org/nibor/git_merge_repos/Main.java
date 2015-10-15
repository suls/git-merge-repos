package org.nibor.git_merge_repos;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.*;

/**
 * Main class for merging repositories via command-line.
 */
public class Main {

	public static class Arguments {
		@Parameter(names = "-pass", password = true, description = "SSH key passphrase")
		private String passphrase;

		@Parameter(names = "--help", help = true, description = "Displaying command line usage")
		private boolean help;

		@Parameter(description = "<repository_url>:<target_directory>...")
		private List<String> repositories = new ArrayList<>();
	}

	private static Pattern REPO_AND_DIR = Pattern.compile("(.*):([^:]+)");

	public static void main(String[] args) throws IOException, GitAPIException, URISyntaxException {

		final Arguments arguments= new Arguments();
		JCommander commander = new JCommander(arguments, args);

		if(arguments.help) {
			commander.usage();
			return;
		}

		if(!arguments.passphrase.isEmpty()) {
			enableSshPassphrase(arguments);
		}

		List<SubtreeConfig> subtreeConfigs = new ArrayList<>();

		for (String arg : arguments.repositories) {
			Matcher matcher = REPO_AND_DIR.matcher(arg);
			if (matcher.matches()) {
				String repositoryUrl = matcher.group(1);
				String directory = matcher.group(2);
				SubtreeConfig config = new SubtreeConfig(directory, new URIish(repositoryUrl));
				subtreeConfigs.add(config);
			} else {
				exitInvalidUsage("invalid argument '" + arg
						+ "', expected '<repository_url>:<target_directory>'");
			}
		}

		if (!subtreeConfigs.isEmpty()) {
			exitInvalidUsage("usage: program <repository_url>:<target_directory>...");


			File outputDirectory = new File("merged-repo");
			String outputPath = outputDirectory.getAbsolutePath();
			System.out.println("Started merging " + subtreeConfigs.size()
					+ " repositories into one, output directory: " + outputPath);

			long start = System.currentTimeMillis();
			RepoMerger merger = new RepoMerger(outputPath, subtreeConfigs);
			List<MergedRef> mergedRefs = merger.run();
			long end = System.currentTimeMillis();

			long timeMs = (end - start);
			printIncompleteRefs(mergedRefs);
			System.out.println("Done, took " + timeMs + " ms");
			System.out.println("Merged repository: " + outputPath);
		}
	}

	private static void enableSshPassphrase(final Arguments arguments) {
		JschConfigSessionFactory sessionFactory = new JschConfigSessionFactory() {
			@Override
			protected void configure(OpenSshConfig.Host host, Session session) {
				CredentialsProvider provider = new CredentialsProvider() {
					@Override
					public boolean isInteractive() {
						return false;
					}

					@Override
					public boolean supports(CredentialItem... items) {
						return true;
					}

					@Override
					public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
						for (CredentialItem item : items) {
							((CredentialItem.StringType) item).setValue(arguments.passphrase);
						}
						return true;
					}
				};
				UserInfo userInfo = new CredentialsProviderUserInfo(session, provider);
				session.setUserInfo(userInfo);
			}
		};
		SshSessionFactory.setInstance(sessionFactory);
	}

	private static void printIncompleteRefs(List<MergedRef> mergedRefs) {
		for (MergedRef mergedRef : mergedRefs) {
			if (!mergedRef.getConfigsWithoutRef().isEmpty()) {
				System.out.println(mergedRef.getRefType() + " '" + mergedRef.getRefName()
						+ "' was not in: " + join(mergedRef.getConfigsWithoutRef()));
			}
		}
	}

	private static String join(Collection<SubtreeConfig> configs) {
		StringBuilder sb = new StringBuilder();
		for (SubtreeConfig config : configs) {
			if (sb.length() != 0) {
				sb.append(", ");
			}
			sb.append(config.getRemoteName());
		}
		return sb.toString();
	}

	private static void exitInvalidUsage(String message) {
		System.err.println(message);
		System.exit(64);
	}
}
