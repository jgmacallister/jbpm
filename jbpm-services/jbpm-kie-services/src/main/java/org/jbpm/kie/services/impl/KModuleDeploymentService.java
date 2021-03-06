/*
 * Copyright 2014 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.kie.services.impl;

import static org.kie.scanner.MavenRepository.getMavenRepository;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManagerFactory;
import javax.xml.bind.annotation.XmlRootElement;

import org.apache.commons.codec.binary.Base64;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.KieContainerImpl;
import org.drools.core.common.ProjectClassLoader;
import org.drools.core.marshalling.impl.ClassObjectMarshallingStrategyAcceptor;
import org.drools.core.marshalling.impl.SerializablePlaceholderResolverStrategy;
import org.drools.core.util.StringUtils;
import org.jbpm.kie.services.impl.bpmn2.BPMN2DataServiceImpl;
import org.jbpm.kie.services.impl.model.ProcessAssetDesc;
import org.jbpm.process.audit.event.AuditEventBuilder;
import org.jbpm.runtime.manager.impl.KModuleRegisterableItemsFactory;
import org.jbpm.runtime.manager.impl.deploy.DeploymentDescriptorManager;
import org.jbpm.runtime.manager.impl.deploy.DeploymentDescriptorMerger;
import org.jbpm.runtime.manager.impl.jpa.EntityManagerFactoryManager;
import org.jbpm.services.api.DefinitionService;
import org.jbpm.services.api.model.DeployedAsset;
import org.jbpm.services.api.model.DeployedUnit;
import org.jbpm.services.api.model.DeploymentUnit;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.marshalling.ObjectMarshallingStrategy;
import org.kie.api.remote.Remotable;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.manager.RegisterableItemsFactory;
import org.kie.api.runtime.manager.RuntimeEnvironmentBuilder;
import org.kie.internal.executor.api.ExecutorService;
import org.kie.internal.runtime.conf.DeploymentDescriptor;
import org.kie.internal.runtime.conf.MergeMode;
import org.kie.internal.runtime.conf.NamedObjectModel;
import org.kie.internal.runtime.conf.ObjectModel;
import org.kie.internal.runtime.conf.ObjectModelResolver;
import org.kie.internal.runtime.conf.ObjectModelResolverProvider;
import org.kie.internal.runtime.conf.PersistenceMode;
import org.kie.scanner.MavenRepository;
import org.scannotation.AnnotationDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class KModuleDeploymentService extends AbstractDeploymentService {

    private static Logger logger = LoggerFactory.getLogger(KModuleDeploymentService.class);
    private static final String DEFAULT_KBASE_NAME = "defaultKieBase";
    
    private DefinitionService bpmn2Service;
    
    private DeploymentDescriptorMerger merger = new DeploymentDescriptorMerger();
    
    private FormManagerService formManagerService;
 
    private ExecutorService executorService;
    
    public void onInit() {
    	EntityManagerFactoryManager.get().addEntityManagerFactory("org.jbpm.domain", getEmf());
    }
    
    @Override
    public void deploy(DeploymentUnit unit) {
    	try {
    		super.deploy(unit);
            if (!(unit instanceof KModuleDeploymentUnit)) {
                throw new IllegalArgumentException("Invalid deployment unit provided - " + unit.getClass().getName());
            }
            KModuleDeploymentUnit kmoduleUnit = (KModuleDeploymentUnit) unit;
            DeployedUnitImpl deployedUnit = new DeployedUnitImpl(unit);
            
            KieContainer kieContainer = kmoduleUnit.getKieContainer();
            ReleaseId releaseId = null;
            if (kieContainer == null) {
	            KieServices ks = KieServices.Factory.get();
	            
	            releaseId = ks.newReleaseId(kmoduleUnit.getGroupId(), kmoduleUnit.getArtifactId(), kmoduleUnit.getVersion());
	
	            MavenRepository repository = getMavenRepository();
	            repository.resolveArtifact(releaseId.toExternalForm());
	
	            kieContainer = ks.newKieContainer(releaseId);
	            
	            kmoduleUnit.setKieContainer(kieContainer);
            }
            releaseId = kieContainer.getReleaseId();
            
            String kbaseName = kmoduleUnit.getKbaseName();
            if (StringUtils.isEmpty(kbaseName)) {
                KieBaseModel defaultKBaseModel = ((KieContainerImpl)kieContainer).getKieProject().getDefaultKieBaseModel();
                if (defaultKBaseModel != null) {
                    kbaseName = defaultKBaseModel.getName();
                } else {
                    kbaseName = DEFAULT_KBASE_NAME;
                }
            }
            InternalKieModule module = (InternalKieModule) ((KieContainerImpl)kieContainer).getKieModuleForKBase(kbaseName);
            if (module == null) {
                throw new IllegalStateException("Cannot find kbase, either it does not exist or there are multiple default kbases in kmodule.xml");
            }
            
            KieBase kbase = kieContainer.getKieBase(kbaseName); 

            //Map<String, String> formsData = new HashMap<String, String>();
            Collection<String> files = module.getFileNames();
            
            processResources(module, files, kieContainer, kmoduleUnit, deployedUnit, releaseId);
            
            if (module.getKieDependencies() != null) {
    	        Collection<InternalKieModule> dependencies = module.getKieDependencies().values();
    	        for (InternalKieModule depModule : dependencies) {

    	        	logger.debug("Processing dependency module " + depModule.getReleaseId());
    	        	files = depModule.getFileNames();

    	        	processResources(depModule, files, kieContainer, kmoduleUnit, deployedUnit, depModule.getReleaseId());
    	        }
            }
            if (module.getJarDependencies() != null && !module.getJarDependencies().isEmpty()) {
            	processClassloader(kieContainer, deployedUnit);
            }
                   
            AuditEventBuilder auditLoggerBuilder = setupAuditLogger(identityProvider, unit.getIdentifier());

            RuntimeEnvironmentBuilder builder = boostrapRuntimeEnvironmentBuilder(
            		kmoduleUnit, deployedUnit, kieContainer, kmoduleUnit.getMergeMode())
                    .knowledgeBase(kbase)
                    .classLoader(kieContainer.getClassLoader());

            builder.registerableItemsFactory(getRegisterableItemsFactory(auditLoggerBuilder, kieContainer, kmoduleUnit));

            commonDeploy(unit, deployedUnit, builder.get(), kieContainer);
            kmoduleUnit.setDeployed(true);
    	} catch (Throwable e) {
    		logger.warn("Unexpected error while deploying unit {}", unit.getIdentifier(), e);
    		// catch all possible errors to be able to report them to caller as RuntimeException
    		throw new RuntimeException(e);
    	}
    }

    protected RegisterableItemsFactory getRegisterableItemsFactory(AuditEventBuilder auditLoggerBuilder, 
    		KieContainer kieContainer,KModuleDeploymentUnit unit) {
    	KModuleRegisterableItemsFactory factory = new KModuleRegisterableItemsFactory(kieContainer, unit.getKsessionName());
    	factory.setAuditBuilder(auditLoggerBuilder);
    	factory.addProcessListener(IdentityProviderAwareProcessListener.class);
    	return factory;
    }
    
    @Override
	public void undeploy(DeploymentUnit unit) {
    	if (!(unit instanceof KModuleDeploymentUnit)) {
            throw new IllegalArgumentException("Invalid deployment unit provided - " + unit.getClass().getName());
        }
        KModuleDeploymentUnit kmoduleUnit = (KModuleDeploymentUnit) unit;
		super.undeploy(unit);

        formManagerService.unRegisterForms( unit.getIdentifier() );

        KieServices ks = KieServices.Factory.get();
		ReleaseId releaseId = ks.newReleaseId(kmoduleUnit.getGroupId(), kmoduleUnit.getArtifactId(), kmoduleUnit.getVersion());
		ks.getRepository().removeKieModule(releaseId);
	}

    protected RuntimeEnvironmentBuilder boostrapRuntimeEnvironmentBuilder(KModuleDeploymentUnit deploymentUnit,
    		DeployedUnit deployedUnit, KieContainer kieContainer, MergeMode mode) {
    	DeploymentDescriptor descriptor = deploymentUnit.getDeploymentDescriptor();
    	if (descriptor == null) {
	    	DeploymentDescriptorManager descriptorManager = new DeploymentDescriptorManager("org.jbpm.domain");
	    	List<DeploymentDescriptor> descriptorHierarchy = descriptorManager.getDeploymentDescriptorHierarchy(kieContainer);
	    	
			descriptor = merger.merge(descriptorHierarchy, mode);
			deploymentUnit.setDeploymentDescriptor(descriptor);
    	} else if (descriptor != null && !deploymentUnit.isDeployed()) {
    		DeploymentDescriptorManager descriptorManager = new DeploymentDescriptorManager("org.jbpm.domain");
	    	List<DeploymentDescriptor> descriptorHierarchy = descriptorManager.getDeploymentDescriptorHierarchy(kieContainer);
	    	
	    	descriptorHierarchy.add(0, descriptor);
	    	descriptor = merger.merge(descriptorHierarchy, mode);
			deploymentUnit.setDeploymentDescriptor(descriptor);
    	}
    	
		// first set on unit the strategy
		deploymentUnit.setStrategy(descriptor.getRuntimeStrategy());
		
		// setting up runtime environment via builder
		RuntimeEnvironmentBuilder builder = null;
		if (descriptor.getPersistenceMode() == PersistenceMode.NONE) {
			builder = RuntimeEnvironmentBuilder.Factory.get().newDefaultInMemoryBuilder();
		} else {
			builder = RuntimeEnvironmentBuilder.Factory.get().newDefaultBuilder();
		}
		// populate various properties of the builder
		EntityManagerFactory emf = EntityManagerFactoryManager.get().getOrCreate(descriptor.getPersistenceUnit());
		builder.entityManagerFactory(emf);
		
		Map<String, Object> contaxtParams = new HashMap<String, Object>();
		contaxtParams.put("entityManagerFactory", emf);
		contaxtParams.put("classLoader", kieContainer.getClassLoader());
		// process object models that are globally configured (environment entries, session configuration)
		for (NamedObjectModel model : descriptor.getEnvironmentEntries()) {
			Object entry = getInstanceFromModel(model, kieContainer, contaxtParams);
			builder.addEnvironmentEntry(model.getName(), entry);
		}
		
		for (NamedObjectModel model : descriptor.getConfiguration()) {
			Object entry = getInstanceFromModel(model, kieContainer, contaxtParams);
			builder.addConfiguration(model.getName(), (String) entry);
		}
		ObjectMarshallingStrategy[] mStrategies = new ObjectMarshallingStrategy[descriptor.getMarshallingStrategies().size() + 1];
		int index = 0;
		for (ObjectModel model : descriptor.getMarshallingStrategies()) {
			Object strategy = getInstanceFromModel(model, kieContainer, contaxtParams);
			mStrategies[index] = (ObjectMarshallingStrategy)strategy;
			index++;
		}
		// lastly add the main default strategy
		mStrategies[index] = new SerializablePlaceholderResolverStrategy(ClassObjectMarshallingStrategyAcceptor.DEFAULT);
		builder.addEnvironmentEntry(EnvironmentName.OBJECT_MARSHALLING_STRATEGIES, mStrategies);
		
		builder.addEnvironmentEntry("KieDeploymentDescriptor", descriptor);
		builder.addEnvironmentEntry("KieContainer", kieContainer);
		if (executorService != null) {
		    builder.addEnvironmentEntry("ExecutorService", executorService);
		}
		// populate all assets with roles for this deployment unit
		List<String> requiredRoles = descriptor.getRequiredRoles(DeploymentDescriptor.TYPE_VIEW);
		if (requiredRoles != null && !requiredRoles.isEmpty()) {
			for (DeployedAsset desc : deployedUnit.getDeployedAssets()) {
				if (desc instanceof ProcessAssetDesc) {
					((ProcessAssetDesc) desc).setRoles(requiredRoles);
				}
			}
		}
		
		// process additional classes
		List<String> remoteableClasses = descriptor.getClasses();
		if (remoteableClasses != null && !remoteableClasses.isEmpty()) {
			for (String className : remoteableClasses) {
				try {
                    ((DeployedUnitImpl)deployedUnit).addClass(kieContainer.getClassLoader().loadClass(className));
                    logger.debug( "Loaded {} into the classpath from deployment descriptor {}", className, kieContainer.getReleaseId().toExternalForm());
                } catch (ClassNotFoundException cnfe) {
                    throw new IllegalArgumentException("Class " + className + " not found in the project");
                } catch (NoClassDefFoundError e) {
                	throw new IllegalArgumentException("Class " + className + " not found in the project");
				}
			}
		}
    		
    	return builder;
    }

    
    protected Object getInstanceFromModel(ObjectModel model, KieContainer kieContainer, Map<String, Object> contaxtParams) {
    	ObjectModelResolver resolver = ObjectModelResolverProvider.get(model.getResolver());
		if (resolver == null) {
		    // if we don't throw an exception here, we have an NPE below.. 
			throw new IllegalStateException("Unable to find ObjectModelResolver for " + model.getResolver());
		}
		
		return resolver.getInstance(model, kieContainer.getClassLoader(), contaxtParams);
    }

	protected void processResources(InternalKieModule module, Collection<String> files,
    		KieContainer kieContainer, DeploymentUnit unit, DeployedUnitImpl deployedUnit, ReleaseId releaseId) {
        for (String fileName : files) {
            if(fileName.matches(".+bpmn[2]?$")) {
                ProcessAssetDesc process;
                try {
                    String processString = new String(module.getBytes(fileName), "UTF-8");
                    process = (ProcessAssetDesc) bpmn2Service.buildProcessDefinition(unit.getIdentifier(), processString, kieContainer, true);
                    if (process == null) {
                    	throw new IllegalArgumentException("Unable to read process " + fileName);
                    }
                    process.setEncodedProcessSource(Base64.encodeBase64String(processString.getBytes()));
                    process.setDeploymentId(unit.getIdentifier());
                    deployedUnit.addAssetLocation(process.getId(), process);
                } catch (UnsupportedEncodingException e) {
                	throw new IllegalArgumentException("Unsupported encoding while processing process " + fileName);
                }
            } else if (fileName.matches(".+ftl$") || fileName.matches(".+form$")) {
                try {
                    String formContent = new String(module.getBytes(fileName), "UTF-8");
                    if (fileName.indexOf( "/" ) != -1) fileName = fileName.substring( fileName.lastIndexOf( "/" ) + 1);
                    formManagerService.registerForm(unit.getIdentifier(), fileName, formContent);
                } catch (UnsupportedEncodingException e) {
                	throw new IllegalArgumentException("Unsupported encoding while processing form " + fileName);
                }
            } else if( fileName.matches(".+class$")) {
                String className = fileName.replaceAll("/", ".");
                className = className.substring(0, fileName.length() - ".class".length());
                try {
                    deployedUnit.addClass(kieContainer.getClassLoader().loadClass(className));
                    logger.debug( "Loaded {} into the classpath from deployment {}", className, releaseId.toExternalForm());
                } catch (ClassNotFoundException cnfe) {
                    throw new IllegalArgumentException("Class " + className + " not found in the project");
                } catch (NoClassDefFoundError e) {
                	throw new IllegalArgumentException("Class " + className + " not found in the project");
				}
            }
        }
    }
	
	protected void processClassloader(KieContainer kieContainer, DeployedUnitImpl deployedUnit) {
		if (kieContainer.getClassLoader() instanceof ProjectClassLoader) {
			ClassLoader parentCl = kieContainer.getClassLoader().getParent();
			if (parentCl instanceof URLClassLoader) {
				URL[] urls = ((URLClassLoader) parentCl).getURLs();
				if (urls == null || urls.length == 0) {
					return;
				}
				
				AnnotationDB db = new AnnotationDB();
				try {
					db.scanArchives(urls);
					Set<String> jaxbClasses = db.getAnnotationIndex().get(XmlRootElement.class.getName());
					Set<String> remoteClasses = db.getAnnotationIndex().get(Remotable.class.getName());
					Set<String> allClasses = new HashSet<String>();
					if (jaxbClasses != null) {
						allClasses.addAll(jaxbClasses);
					}
					if (remoteClasses != null) {
						allClasses.addAll(remoteClasses);
					}
					for (String className : allClasses) {
						try {
		                    deployedUnit.addClass(kieContainer.getClassLoader().loadClass(className));
		                    logger.debug( "Loaded {} into the classpath from deployment {}", className, kieContainer.getReleaseId().toExternalForm());
		                } catch (ClassNotFoundException cnfe) {
		                    throw new IllegalArgumentException("Class " + className + " not found in the project");
		                }
					}
				} catch (IOException e) {
					logger.warn("Encountered error while scanning classes {}", e.getMessage());
				}
				
			}
		}
	}

	public void setBpmn2Service(DefinitionService bpmn2Service) {
	    this.bpmn2Service = bpmn2Service;
	}

	public void setMerger(DeploymentDescriptorMerger merger) {
		this.merger = merger;
	}

    public void setFormManagerService(FormManagerService formManagerService) {
        this.formManagerService = formManagerService;
    }
        
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }
    

	@Override
	public void activate(String deploymentId) {
		DeployedUnit deployed = getDeployedUnit(deploymentId);
		if (deployed != null) {
			((DeployedUnitImpl)deployed).setActive(true);
			notifyOnActivate(deployed.getDeploymentUnit(), deployed);
		}
	}

	@Override
	public void deactivate(String deploymentId) {
		DeployedUnit deployed = getDeployedUnit(deploymentId);
		if (deployed != null) {
			((DeployedUnitImpl)deployed).setActive(false);
			notifyOnDeactivate(deployed.getDeploymentUnit(), deployed);
		}
	}
	
	
}
