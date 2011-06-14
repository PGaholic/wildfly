/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.ejb3.deployment.processors.dd;

import org.jboss.as.ee.component.Attachments;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ejb3.EJBMethodIdentifier;
import org.jboss.as.ejb3.component.EJBComponentDescription;
import org.jboss.as.ejb3.component.MethodIntf;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndex;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndexUtil;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.logging.Logger;
import org.jboss.metadata.ejb.spec.AssemblyDescriptorMetaData;
import org.jboss.metadata.ejb.spec.EnterpriseBeanMetaData;
import org.jboss.metadata.ejb.spec.ExcludeListMetaData;
import org.jboss.metadata.ejb.spec.MethodInterfaceType;
import org.jboss.metadata.ejb.spec.MethodMetaData;
import org.jboss.metadata.ejb.spec.MethodParametersMetaData;
import org.jboss.metadata.ejb.spec.MethodsMetaData;
import org.jboss.modules.Module;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

/**
 * User: Jaikiran Pai
 */
public class ExcludeListDDProcessor extends AbstractEjbXmlDescriptorProcessor<EnterpriseBeanMetaData> {

    private static final Logger logger = Logger.getLogger(ExcludeListDDProcessor.class);

    @Override
    protected Class<EnterpriseBeanMetaData> getMetaDataType() {
        return EnterpriseBeanMetaData.class;
    }

    @Override
    protected void processBeanMetaData(EnterpriseBeanMetaData beanMetaData, DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final String ejbName = beanMetaData.getEjbName();
        final AssemblyDescriptorMetaData assemblyDescriptor = beanMetaData.getAssemblyDescriptor();
        if (assemblyDescriptor == null) {
            return;
        }
        final ExcludeListMetaData excludeList = assemblyDescriptor.getExcludeListByEjbName(ejbName);
        if (excludeList == null) {
            return;
        }
        final MethodsMetaData methods = excludeList.getMethods();
        if (methods == null || methods.isEmpty()) {
            return;
        }
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);
        final DeploymentReflectionIndex deploymentReflectionIndex = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.REFLECTION_INDEX);
        final EJBComponentDescription ejbComponentDescription = (EJBComponentDescription) moduleDescription.getComponentByName(ejbName);
        final Class<?> ejbClass;
        try {
            ejbClass = module.getClassLoader().loadClass(beanMetaData.getEjbClass());
        } catch (ClassNotFoundException e) {
            throw new DeploymentUnitProcessingException("Could not load EJB class " + beanMetaData.getEjbClass());
        }
        final ClassReflectionIndex classReflectionIndex = deploymentReflectionIndex.getClassIndex(ejbClass);

        for (final MethodMetaData denyAllMethod : methods) {
            final String methodName = denyAllMethod.getMethodName();
            final MethodIntf methodIntf = this.getMethodIntf(denyAllMethod);
            // style 1
            //            <method>
            //                <ejb-name>EJBNAME</ejb-name>
            //                <method-name>*</method-name>
            //            </method>
            if (methodName.equals("*")) {
                // if method name is * then it means all methods, which actually implies a class level @DenyAll (a.k.a exclude-list)
                // now check if it specifies the optional method-inf. If it doesn't then it applies to all views
                if (methodIntf == null) {
                    ejbComponentDescription.applyDenyAllOnAllMethodsOfAllViews();
                } else {
                    ejbComponentDescription.applyDenyAllOnAllMethodsOfViewType(methodIntf);
                }
            } else {
                final MethodParametersMetaData methodParams = denyAllMethod.getMethodParams();
                // style 2
                //            <method>
                //                <ejb-name>EJBNAME</ejb-name>
                //                <method-name>METHOD</method-name>
                //              </method>
                if (methodParams == null || methodParams.isEmpty()) {
                    final Collection<Method> denyAllApplicableMethods = ClassReflectionIndexUtil.findAllMethodsByName(deploymentReflectionIndex, classReflectionIndex, methodName);
                    // just log a WARN message and proceed, in case there was no method by that name
                    if (denyAllApplicableMethods.isEmpty()) {
                        logger.warn("No method named: " + methodName + " found on EJB: " + ejbName + " while processing exclude-list element in ejb-jar.xml");
                        continue;
                    }
                    // apply the @DenyAll/exclude-list
                    this.applyDenyAll(ejbComponentDescription, methodIntf, denyAllApplicableMethods);

                } else {
                    // style 3
                    //            <method>
                    //                <ejb-name>EJBNAME</ejb-name>
                    //                <method-name>METHOD</method-name>
                    //                <method-params>
                    //                <method-param>PARAMETER_1</method-param>
                    //                ...
                    //                <method-param>PARAMETER_N</method-param>
                    //                </method-params>
                    //
                    //              </method>
                    final String[] paramTypes = methodParams.toArray(new String[methodParams.size()]);
                    final Collection<Method> denyAllApplicableMethods = ClassReflectionIndexUtil.findMethods(deploymentReflectionIndex, classReflectionIndex, methodName, paramTypes);
                    // just log a WARN message and proceed, in case there was no method by that name and param types
                    if (denyAllApplicableMethods.isEmpty()) {
                        logger.warn("No method named: " + methodName + " with param types: " + paramTypes + " found on EJB: " + ejbName + " while processing exclude-list element in ejb-jar.xml");
                        continue;
                    }
                    // apply the @DenyAll/exclude-list
                    this.applyDenyAll(ejbComponentDescription, methodIntf, denyAllApplicableMethods);
                }
            }

        }

    }

    private MethodIntf getMethodIntf(final MethodMetaData method) {
        final MethodInterfaceType methodInterfaceType = method.getMethodIntf();
        if (methodInterfaceType == null) {
            return null;
        }
        switch (methodInterfaceType) {
            case Local:
                return MethodIntf.LOCAL;
            case Remote:
                return MethodIntf.REMOTE;
            case Home:
                return MethodIntf.HOME;
            case LocalHome:
                return MethodIntf.LOCAL_HOME;
            case ServiceEndpoint:
                return MethodIntf.SERVICE_ENDPOINT;
            case MessageEndpoint:
                return MethodIntf.MESSAGE_ENDPOINT;
            case Timer:
                return MethodIntf.TIMER;
            default:
                throw new RuntimeException("Unknown method-intf " + methodInterfaceType + " on method " + method);
        }
    }

    private void applyDenyAll(final EJBComponentDescription ejbComponentDescription, final MethodIntf viewType, final Collection<Method> denyAllApplicableMethods) {
        for (final Method denyAllApplicableMethod : denyAllApplicableMethods) {
            final EJBMethodIdentifier ejbMethodIdentifier = EJBMethodIdentifier.fromMethod(denyAllApplicableMethod);
            if (viewType == null) {
                ejbComponentDescription.applyDenyAllOnAllViewsForMethod(ejbMethodIdentifier);
            } else {
                ejbComponentDescription.applyDenyAllOnViewTypeForMethod(viewType, ejbMethodIdentifier);
            }
        }
    }

}
