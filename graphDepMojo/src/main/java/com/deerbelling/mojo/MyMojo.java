package com.deerbelling.mojo;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/**
 * Goal which touches a timestamp file.
 *
 * @goal touch
 * @requiresDependencyResolution test
 * 
 */
public class MyMojo extends AbstractMojo {

	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	private static final String LABEL_REF_COUNT = "###################### %s / LIB REFERENCES COUNT #####################";
	private static final String LABEL_REF_UNUSED = "##################### %s / LIB REFERENCES UNUSED #####################";
	private static final String LABEL_NON_DECLARED_REF_COUNT = "############### %s / NON DECLARED LIB REFERENCES COUNT ###############";

	@SuppressWarnings("unchecked")
	public void execute() throws MojoExecutionException {

		Map<String, Set<File>> classMap = new HashMap<String, Set<File>>();
		Map<String, Integer> countDependenciesMap = new HashMap<String, Integer>();
		Map<String, Integer> countExternalLibMap = new HashMap<String, Integer>();

		try {

			List<String> classpathElements = project.getCompileClasspathElements();
			classpathElements.addAll(project.getTestClasspathElements());

			for (Artifact artifact : (Set<Artifact>) project.getDependencyArtifacts()) {
				if (artifact != null && artifact.getFile() != null) {
					countDependenciesMap.put(artifact.getFile().toString(), 0);
				}
			}

			URLClassLoader urlClassLoader = initClassLoader(classpathElements);
			Thread.currentThread().setContextClassLoader(urlClassLoader);

			final ClassPool pool = new ClassPool(ClassPool.getDefault());
			setClasspath(pool, classpathElements);

			getLog().debug("Project classpath ************************ ");

			for (Object src : classpathElements) {
				getLog().debug("- " + src.toString());

				if (src instanceof String) {

					String dirname = (String) src;

					Set<File> files = new HashSet<File>();

					findFile(new File(dirname), files);

					if (!files.isEmpty()) {
						classMap.put(dirname, files);
					}
				}
			}

			getLog().debug("****************************************** ");

			for (String dirname : classMap.keySet()) {

				for (File file : classMap.get(dirname)) {

					getLog().debug("   dirname : " + dirname);
					getLog().debug("   file name : " + file.getName());
					getLog().debug("   file path: " + file.getPath());

					String classname = file.getPath().substring(dirname.length(), file.getPath().lastIndexOf('.'));

					classname = (classname.startsWith(File.separator))
							? classname.substring(1).replace(File.separatorChar, '.')
							: classname.replace(File.separatorChar, '.');

					getLog().debug("   class name : " + classname);

					try {
						CtClass ctClass = pool.get(classname);
						getLog().debug("javassist class : " + ctClass.getName());
						CtMethod[] methods = ctClass.getDeclaredMethods();
						for (CtMethod method : methods) {
							getLog().debug("******* method : " + method.getLongName());

							method.instrument(new ExprEditor() {

								@Override
								public void edit(MethodCall m) throws CannotCompileException {

									if (m != null && StringUtils.isNotBlank(m.getClassName())) {

										getLog().debug("** callee ClassName : " + m.getClassName());
										getLog().debug("** callee MethodName : " + m.getMethodName());
										getLog().debug("** callee Signature : " + m.getSignature());

										String className = m.getClassName();

										if (method.getDeclaringClass() != null
												&& StringUtils.isNotBlank(method.getDeclaringClass().getName())
												&& !className.equals(method.getDeclaringClass().getName())) {
											className = method.getDeclaringClass().getName();
											getLog().debug("** callee DeclaringClass : " + className);
										}

										String classFilePath = m.getClassName().replace('.', '/') + ".class";
										URL url = urlClassLoader.getResource(classFilePath);

										getLog().debug("** find in : " + url);

										if (url != null) {
											String file = url.getFile();
											int endIndex = (file.contains("!")) ? file.lastIndexOf('!') : file.length();
											String lib = file.substring((file.lastIndexOf(':') + 1), endIndex);
											if (lib.endsWith(".class")) {
												if (lib.startsWith(project.getBuild().getOutputDirectory())) {
													lib = project.getBuild().getOutputDirectory();
													if (countDependenciesMap.containsKey(lib)) {
														int count = countDependenciesMap.get(lib).intValue() + 1;
														countDependenciesMap.replace(lib, count);
													} else {
														countDependenciesMap.put(lib, 1);
													}
												} else if (lib
														.startsWith(project.getBuild().getTestOutputDirectory())) {
													lib = project.getBuild().getTestOutputDirectory();
													if (countDependenciesMap.containsKey(lib)) {
														int count = countDependenciesMap.get(lib).intValue() + 1;
														countDependenciesMap.replace(lib, count);
													} else {
														countDependenciesMap.put(lib, 1);
													}
												} else {
													getLog().error(" * lib not found : " + lib);
												}
											} else {
												getLog().debug("** lib name : " + lib);

												if (countDependenciesMap.containsKey(lib)) {
													int count = countDependenciesMap.get(lib).intValue() + 1;
													countDependenciesMap.replace(lib, count);
												} else if (countExternalLibMap.containsKey(lib)) {
													int count = countExternalLibMap.get(lib).intValue() + 1;
													countExternalLibMap.replace(lib, count);
												} else {
													countExternalLibMap.put(lib, 1);
												}
											}
										}

									} else {
										getLog().error(" * 1 reference not found in : " + method.getLongName());
									}
									getLog().debug("********************************");
								}
							});
						}

					} catch (NotFoundException e) {
						getLog().error(e);
					} catch (CannotCompileException e) {
						getLog().error(e);
					}
				}
			}

			Set<String> libRefCount = new HashSet<>();
			Set<String> libUnsedRef = new HashSet<>();

			for (String libKey : countDependenciesMap.keySet()) {
				int countRef = countDependenciesMap.get(libKey);
				if (countRef == 0) {
					libUnsedRef.add("# " + libKey);
				} else {
					libRefCount.add("# " + libKey + " : " + countDependenciesMap.get(libKey));
				}
			}

			getLog().info(String.format(LABEL_REF_COUNT, project.getName()));
			for (String libRef : libRefCount) {
				getLog().info(libRef);
			}
			getLog().info(String.format(LABEL_REF_UNUSED, project.getName()));
			for (String libUnsed : libUnsedRef) {
				getLog().info(libUnsed);
			}
			getLog().info(String.format(LABEL_NON_DECLARED_REF_COUNT, project.getName()));
			for (String libKey : countExternalLibMap.keySet()) {
				getLog().info("# " + libKey + " : " + countExternalLibMap.get(libKey));
			}
			getLog().info(StringUtils.repeat('#', LABEL_REF_COUNT.length() + project.getName().length()));

		} catch (DependencyResolutionRequiredException e) {
			throw new MojoExecutionException("Required dependecy problem.", e);
		}
	}

	private void findFile(File file, Set<File> classList) {
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				if (child != null)
					findFile(child, classList);
			}

		} else if (file.isFile() && file.getPath().endsWith(".class")) {
			classList.add(file);
		}
	}

	private URLClassLoader initClassLoader(List<String> classpathElements) throws MojoExecutionException {
		try {
			URL urls[] = new URL[classpathElements.size()];

			for (int i = 0; i < classpathElements.size(); ++i) {
				urls[i] = new File((String) classpathElements.get(i)).toURI().toURL();
			}
			return new URLClassLoader(urls);
		} catch (Exception e) {
			throw new MojoExecutionException("Problem to create custom classloader.", e);
		}
	}

	private void setClasspath(ClassPool classPool, List<String> classpathElements) throws MojoExecutionException {
		try {
			for (int i = 0; i < classpathElements.size(); ++i) {
				classPool.appendClassPath((String) classpathElements.get(i));
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Problem to add classpath in javassist class pool.", e);
		}
	}
}