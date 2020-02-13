package com.liuqi.tool.idea.plugin;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.PsiDirectoryFactory;
import com.liuqi.tool.idea.plugin.utils.MyStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * @author LiuQi 2019/7/11-10:50
 * @version V1.0
 **/
public class GeneratorAction extends MyAnAction {
    private Map<String, PsiDirectory> directoryMap = new HashMap<>(16);

    @Override
    public synchronized void actionPerformed(@NotNull AnActionEvent anActionEvent) {
        PsiClass aClass = this.getEditingClass(anActionEvent);
        if (null == aClass) {
            return;
        }

        if (null == aClass.getAnnotation("javax.persistence.Entity")) {
            // 只处理被Entity注解的类
            return;
        }

        // 加载所有目录
        loadDirs();

        // 先创建Service目录
        EntityClasses entityClasses = new EntityClasses()
                .setServiceDirectory(createServiceDirectory())
                .setEntityClass(aClass);

        // 在它所在包的同级的repository中创建Repository
        WriteCommandAction.runWriteCommandAction(project, () ->
                createRepository(entityClasses));
    }

    private void loadDirs() {
        PsiDirectory rootDirectory = containerDirectory.getParentDirectory();
        if (null == rootDirectory) {
            rootDirectory = containerDirectory;
        } else {
            while (null != rootDirectory.getParent()) {
                if (rootDirectory.getParent().getName().equals("src")) {
                    break;
                }

                rootDirectory = rootDirectory.getParent();
            }
        }

        loadDir(rootDirectory);
    }

    private void loadDir(PsiDirectory psiDirectory) {
        directoryMap.put(psiDirectory.getName(), psiDirectory);
        for (PsiDirectory subdirectory : psiDirectory.getSubdirectories()) {
            loadDir(subdirectory);
        }
    }

    private Optional<PsiDirectory> findDir(String dirName) {
        return Optional.ofNullable(directoryMap.get(dirName));
    }

    /**
     * 根据实体类创建Repository、Service等操作类
     *
     * @param aClass          实体类
     * @param repositoryClass 数据库操作类
     */
    private void createClasses(EntityClasses entityClasses) {
        String className = entityClasses.getEntityClassName();
        assert className != null;

        String entityName = className.replace("Entity", "");

        // 在service下创建dto目录
        PsiDirectory dtoDirectory = findDir("dto").orElseGet(() -> psiUtils.getOrCreateSubDirectory(entityClasses.getServiceDirectory(), "dto"));

        // 先检查是否存在AbstractBaseDTO对象，如果存在的话DTO对象需要继承自该对象
        Optional<PsiClass> abstractBaseDTOOptional = psiUtils.findClass("AbstractBaseDTO");

        String dtoContent = "public class " + entityName + "DTO";
        boolean extendFromBaseDTO = false;
        for (PsiClassType extendsListType : entityClasses.getEntityClass().getExtendsListTypes()) {
            extendFromBaseDTO = extendsListType.getName().contains("AbstractBaseEntity");
        }
        if (abstractBaseDTOOptional.isPresent() && extendFromBaseDTO) {
            // 存在时
            dtoContent += " extends AbstractBaseDTO";
        }

        dtoContent += "{}";

        // 根据Entity对象创建DTO对象
        boolean pExtendFromBaseDTO = extendFromBaseDTO;
        ClassCreator.of(project).init(entityName + "DTO", dtoContent)
                .copyFields(entityClasses.getEntityClass())
                .importClassIf("AbstractBaseDTO", () -> pExtendFromBaseDTO)
                .addTo(dtoDirectory)
                .and(dtoClass -> createMapperClass(entityClasses.setDtoClass(dtoClass)));
    }

    /**
     * 创建Service包目录
     *
     * @param parentDirectory 父级目录
     * @return 创建的目录
     */
    private PsiDirectory createServiceDirectory() {
        return findDir("service")
                .orElseGet(() -> {
                    PsiDirectory serviceDirectory;

                    if (null == containerDirectory.getParent()) {
                        serviceDirectory = psiUtils.getOrCreateSubDirectory(containerDirectory, "service");
                    } else {
                        if (null == containerDirectory.getParent().getParent()) {
                            serviceDirectory = psiUtils.getOrCreateSubDirectory(containerDirectory.getParent(), "service");
                        } else {
                            serviceDirectory = psiUtils.getOrCreateSubDirectory(containerDirectory.getParent().getParent(), "service");
                        }
                    }
                    return serviceDirectory;
                });
    }

    /**
     * 创建Mapper对象
     */
    private void createMapperClass(EntityClasses entityClasses) {
        String entityName = entityClasses.getEntityName();

        // 增加mapper对象
        PsiDirectory mapperDirectory = findDir("mapper").orElseGet(() -> psiUtils.getOrCreateSubDirectory(entityClasses.serviceDirectory, "mapper"));

        // 先创建EntityMapper对象
        // 先检查EntityMapper是否存在
        Optional<PsiClass> entityMapperClassOptional = psiUtils.findClass("EntityMapper");
        Consumer<PsiClass> createMapperFunction = entityMapperClass -> {
            String mapperName = entityName + "Mapper";
            ClassCreator.of(project).init(mapperName,
                    "@Mapper(componentModel = \"spring\")" +
                            "public interface " + mapperName + " extends EntityMapper<"
                            + entityClasses.getDtoClass().getName() + ", " + entityClasses.getEntityClass().getName() + "> {}")
                    .importClass("org.mapstruct.Mapper")
                    .importClass(entityClasses.getEntityClass())
                    .addTo(mapperDirectory)
                    .and(mapperClass -> {
                        psiUtils.importClass(mapperClass, entityClasses.getDtoClass(), entityMapperClass);

                        // 先增加MyBatis的Dao对象及XML文件
                        addQuery(entityClasses.setMapperClass(mapperClass));
                    });
        };

        if (entityMapperClassOptional.isPresent()) {
            // 创建Mapper对象
            createMapperFunction.accept(entityMapperClassOptional.get());
        } else {
            // 不存在时，先创建EntityMapper然后再创建Mapper
            ClassCreator.of(project).init("EntityMapper", "public interface EntityMapper<D, E> {\n" +
                    "    E toEntity(D dto);\n" +
                    "    D toDto(E entity);\n" +
                    "    List<E> toEntity(List<D> dtoList);\n" +
                    "    List <D> toDto(List<E> entityList);\n" +
                    "}")
                    .importClass("java.util.List")
                    .addTo(mapperDirectory)
                    .and(createMapperFunction);
        }

    }

    /**
     * 增加MyBatis相关文件
     */
    private void addQuery(EntityClasses entityClasses) {
        // 先创建Query对象
        PsiDirectory queryDirectory = findDir("query")
                .orElseGet(() -> psiUtils.getOrCreateSubDirectory(entityClasses.getServiceDirectory(), "query"));
        ClassCreator.of(project)
                .init(entityClasses.getEntityName() + "Query", "public class " + entityClasses.getEntityName() + "Query {private Integer page;  \nprivate Integer size;  }")
                .addGetterAndSetterMethods()
                .addTo(queryDirectory)
                .and(queryClass -> {
                    entityClasses.setQueryClass(queryClass).setQueryDirectory(queryDirectory);

                    // 在Repository的同级目录下创建dao目录及dao对象
                    addDao(entityClasses);
                });
    }

    /**
     * 添加Mybatis Dao
     *
     * @param entityClasses 类集
     */
    private void addDao(EntityClasses entityClasses) {
        PsiDirectory daoDirectory = findDir("dao").orElseGet(() -> null == containerDirectory.getParent() ? containerDirectory :
                psiUtils.getOrCreateSubDirectory(containerDirectory.getParent(), "dao"));

        ClassCreator.of(project).init(entityClasses.getEntityName() + "Dao",
                "@Mapper public interface " + entityClasses.getEntityName() + "Dao {" +
                        "List<" + entityClasses.getDtoClass().getName() + "> query(" + entityClasses.getQueryClass().getName() + " query); " +
                        "void batchAdd(@Param(\"list\") List<" + entityClasses.getDtoClass().getName() + "> dataList);" +
                        "}")
                .importClass("java.util.List")
                .importClass("org.apache.ibatis.annotations.Mapper")
                .importClass("org.apache.ibatis.annotations.Param")
                .addTo(daoDirectory)
                .and(daoClass -> {
                    psiUtils.importClass(daoClass, entityClasses.getQueryClass(), entityClasses.getDtoClass());
                    createDaoMappingFile(entityClasses.setDaoClass(daoClass));
                });
    }

    /**
     * 创建MyBatis映射文件
     *
     * @param entityClasses 实体相关类集合
     */
    private void createDaoMappingFile(EntityClasses entityClasses) {
        // 获取mappers目录，在resources目录下，如果没有这个目录，那么创建一个目录
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        List<VirtualFile> sourceRoots = rootManager.getSourceRoots(JavaModuleSourceRootTypes.RESOURCES);
        VirtualFile resourceDirectory = sourceRoots.get(0);
        VirtualFile mappersDirectory = resourceDirectory.findChild("mappers");
        if (null == mappersDirectory) {
            try {
                mappersDirectory = resourceDirectory.createChildDirectory(this, "mappers");
            } catch (IOException e) {
                e.printStackTrace();
                mappersDirectory = resourceDirectory;
            }
        }

        PsiDirectory psiDirectory = PsiDirectoryFactory.getInstance(project).createDirectory(mappersDirectory);

        String fileName = entityClasses.getEntityName() + "Dao.xml";
        PsiFile psiFile = psiDirectory.findFile(fileName);
        if (null == psiFile) {

            String daoPackage = ((PsiJavaFile) entityClasses.getDaoClass().getContainingFile()).getPackageName();
            String dtoPackage = ((PsiJavaFile) entityClasses.getDtoClass().getContainingFile()).getPackageName();
            StringBuilder content = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                    "<!DOCTYPE mapper\n" +
                    "        PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\"\n" +
                    "        \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">" +
                    "<mapper namespace=\"" + daoPackage + "." + entityClasses.getDaoClass().getName() + "\">");

            // 增加resultMap映射
            content.append("<resultMap id=\"resultMap\" type=\"").append(dtoPackage).append(".").append(entityClasses.getDtoClass().getName()).append("\">");
            PsiClass entityClass = entityClasses.getEntityClass();

            StringBuilder columns = new StringBuilder();
            StringBuilder insertColumns = new StringBuilder();
            StringBuilder insertFields = new StringBuilder();
            for (PsiField field : entityClass.getAllFields()) {
                String fieldName = field.getName();
                String str = Arrays.stream(Objects.requireNonNull(StringUtils.splitByCharacterTypeCamelCase(fieldName)))
                        .reduce((s1, s2) -> s1.toLowerCase().concat("_").concat(s2.toLowerCase())).orElse("");

                content.append("<result property=\"").append(fieldName).append("\" column=\"").append(str).append("\"");

                // 如果是枚举类
                String typeClassName = field.getType().getCanonicalText();
                psiUtils.findClass(typeClassName).ifPresent(typeClass -> {
                    if (typeClass.isEnum()) {
                        content.append(" typeHandler=\"org.apache.ibatis.type.EnumOrdinalTypeHandler\"");
                    }
                });

                content.append("/>");

                if (0 == columns.length()) {
                    columns.append("t1.").append(str);
                    insertColumns.append(str);
                    insertFields.append("#{item.").append(fieldName).append("}");
                } else {
                    columns.append(",").append("t1.").append(str);
                    insertColumns.append(",").append(str);
                    insertFields.append(",#{item.").append(fieldName).append("}");
                }
            }

            // 获取表名
            String tableName = "tableName";
            PsiAnnotation annotation = entityClass.getAnnotation("javax.persistence.Table");
            if (null != annotation) {
                PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("name");
                if (null != memberValue) {
                    tableName = memberValue.getText();
                }
            }

            tableName = tableName.replaceAll("\"", "");

            content.append("</resultMap>")
                    .append("<sql id=\"columns\">\n")
                    .append("select \n")
                    .append(columns.toString())
                    .append("\n from ")
                    .append(tableName)
                    .append(" t1 \n</sql>");

            content.append("<select id=\"query\" parameterType=\"")
                    .append(psiUtils.getPackageAndName(entityClasses.getQueryClass()))
                    .append("\" resultMap=\"resultMap\">")
                    .append("<include refid=\"columns\"/>")
                    .append("</select>");

            // 增加批量新增语句
            content.append("<insert id=\"batchAdd\" parameterType=\"")
                    .append(psiUtils.getPackageAndName(entityClasses.getDtoClass()))
                    .append("\">")
                    .append("\ninsert into ")
                    .append(tableName)
                    .append("(")
                    .append(insertColumns.toString())
                    .append(") values <foreach collection=\"list\" item=\"item\" open=\"\" close=\"\" separator=\",\">\n")
                    .append("(")
                    .append(insertFields.toString())
                    .append(")")
                    .append("\n</foreach></insert>")
            ;

            content.append("</mapper>");

            psiFile = PsiFileFactory.getInstance(project).createFileFromText(fileName, XMLLanguage.INSTANCE,
                    content);
            psiUtils.format(psiFile);
            psiDirectory.add(psiFile);
        }

        createService(entityClasses);
    }

    private void createService(EntityClasses entityClasses) {
        // 增加服务接口
        String serviceName = entityClasses.getEntityName().concat("Service");

        String content = "public interface " +
                serviceName +
                "{" +
                "void save(" + entityClasses.getDtoClass().getName() + " dto); " +
                "\nvoid save(List<" + entityClasses.getDtoClass().getName() + "> dtos); " +
                "\nvoid delete(Long id);" + "Optional<" + entityClasses.getDtoClass().getName() + "> findOne(Long id); " +
                "\nList<" + entityClasses.getDtoClass().getName() + "> findAll(); " +
                "\nList<" + entityClasses.getDtoClass().getName() + "> query(" + entityClasses.getQueryClass().getName() + " query); " +
                "\nPageInfo<" + entityClasses.getDtoClass().getName() + "> pageQuery(" + entityClasses.getQueryClass().getName() + " query); " +
                "}";
        ClassCreator.of(project).init(serviceName, content)
                .importClass(entityClasses.dtoClass)
                .importClass("java.util.Optional")
                .importClass("java.util.List")
                .importClass("com.github.pagehelper.PageInfo")
                .addTo(entityClasses.serviceDirectory)
                .and(serviceClass -> {
                    psiUtils.importClass(serviceClass, entityClasses.getQueryClass());
                    psiUtils.importClass(serviceClass, entityClasses.getQueryClass());
                    createServiceImpl(entityClasses.setServiceClass(serviceClass));
                });
    }

    /**
     * 创建服务实现类
     *
     * @param aClass            实体类
     * @param repositoryClass   数据库操作类
     * @param pServiceDirectory 服务类所在目录
     * @param serviceClass      服务类
     */
    private void createServiceImpl(EntityClasses entityClasses) {
        String serviceName = entityClasses.getServiceClass().getName();
        // 增加接口服务实现
        PsiDirectory serviceImplDirectory = psiUtils.getOrCreateSubDirectory(entityClasses.getServiceDirectory(), "impl");

        StringBuilder content = new StringBuilder("@Service public class ")
                .append(serviceName)
                .append("Impl ")
                .append(" implements ").append(entityClasses.getServiceClass().getName())
                .append("{");

        PsiClass repositoryClass = entityClasses.getRepositoryClass();
        String saveAllMethod = "save";
        if (0 != repositoryClass.findMethodsByName("saveAll", true).length) {
            saveAllMethod = "saveAll";
        }

        String daoFieldName = StringUtils.uncapitalize(entityClasses.getDaoClass().getName());

        content.append("@Resource private ").append(entityClasses.getMapperClass().getName()).append(" mapper; \n")
                .append("\n@Resource private ").append(entityClasses.getRepositoryClass().getName()).append(" repository; \n")
                .append("\n@Resource private ").append(entityClasses.getDaoClass().getName()).append(" ").append(daoFieldName).append("; \n")
                .append("\n@Override @Transactional public void save(").append(entityClasses.getDtoClass().getName()).append(" dto) { repository.save(mapper.toEntity(dto));}")
                .append("\n@Override @Transactional  public void save(List<").append(entityClasses.getDtoClass().getName()).append("> dtos) { repository.").append(
                saveAllMethod).append("(mapper.toEntity(dtos)); }")
                .append("\n@Override @Transactional  public void delete(Long id) { repository.delete(id); }")
                .append("\n@Override @Transactional(readOnly = true)  public Optional<").append(entityClasses.getDtoClass().getName()).append(
                "> findOne(Long id) { return Optional.ofNullable(mapper.toDto(repository.findOne(id))); }")
                .append("\n@Override @Transactional(readOnly = true) public List<").append(entityClasses.getDtoClass().getName()).append(
                "> findAll() { return mapper.toDto(repository.findAll()); }")
                .append("\n@Override @Transactional(readOnly = true) public List<").append(entityClasses.getDtoClass().getName()).append("> query(")
                    .append(entityClasses.getQueryClass().getName()).append(" query) { return ").append(daoFieldName).append(".query(query);}")
                .append("\n@Override @Transactional(readOnly = true) public PageInfo<").append(entityClasses.getDtoClass().getName()).append("> pageQuery(").append(
                entityClasses.getQueryClass().getName()).append(" query) {")
                .append("if (null != query.getSize() && null != query.getPage()) {PageHelper.startPage(query.getPage(), query.getSize()); }")
                .append("return new PageInfo<>(").append(daoFieldName).append(".query(query));}")
                .append("}");

        ClassCreator.of(project).init(serviceName + "Impl", content.toString())
                .importClass(entityClasses.getEntityClass())
                .importClass("javax.annotation.Resource")
                .importClass("org.springframework.stereotype.Service")
                .importClass("Transactional")
                .importClass("java.util.Optional")
                .importClass("java.util.List")
                .importClass("PageHelper")
                .importClass("AbstractBaseEntityService")
                .importClass("com.github.pagehelper.PageInfo")
                .addTo(serviceImplDirectory)
                .and(implClass -> {
                    psiUtils.importClass(implClass, entityClasses.getServiceClass(),
                            entityClasses.getRepositoryClass(), entityClasses.getMapperClass(), entityClasses.getDtoClass(),
                            entityClasses.getQueryClass(),
                            entityClasses.getDaoClass());

                    createController(entityClasses);
                });
    }

    /**
     * 创建控制器
     *
     * @param entityClasses 相关类
     */
    private void createController(EntityClasses entityClasses) {
        // 在Service同目录下获取controller或者web目录
        PsiDirectory parentDirectory = entityClasses.getServiceDirectory().getParent();
        PsiDirectory controllerDirectory = parentDirectory.findSubdirectory("controller");
        if (null == controllerDirectory) {
            controllerDirectory = parentDirectory.findSubdirectory("web");
            if (null != controllerDirectory) {
                if (null != controllerDirectory.findSubdirectory("rest")) {
                    controllerDirectory = controllerDirectory.findSubdirectory("rest");
                }
            }
        }

        if (null == controllerDirectory) {
            controllerDirectory = findDir("controller").orElseGet(() -> parentDirectory.createSubdirectory("controller"));
        }

        // 先去目录下随便找一个Controller，获取其路径，判断其是否以api开头，从而决定当前路径是否要以api开头
        PsiFile[] files = controllerDirectory.getFiles();

        // 如果一个Controller都没有，则默认以api开头
        String prefix = "/api/";
        if (0 != files.length) {
            for (PsiFile file : files) {
                if (file instanceof PsiJavaFile) {
                    Optional<String> value = psiUtils.getAnnotationValue(file, "org.springframework.web.bind.annotation.RequestMapping", "value");
                    if (value.isPresent()) {
                        if (!value.get().startsWith("/api")) {
                            prefix = "/";
                        }
                    }
                }
            }
        }

        // 判断是否要加API的注解
        Optional<PsiClass> apiClass = psiUtils.findClass("io.swagger.annotations.Api");
        boolean useAPI = apiClass.isPresent();

        // 检查是否有名称为BaseController或者BaseResource的类
        Optional<PsiClass> baseClassOptional = psiUtils.findClass("BaseController");
        String suffix = "Controller";
        if (!baseClassOptional.isPresent()) {
            baseClassOptional = psiUtils.findClass("BaseResource");
            suffix = "Resource";
            if (!baseClassOptional.isPresent()) {
                suffix = "Controller";
            }
        }

        String entityName = entityClasses.getEntityName();
        String controllerPath = Arrays.stream(StringUtils.splitByCharacterTypeCamelCase(entityName))
                .reduce((s1, s2) -> s1.toLowerCase().concat("-").concat(s2.toLowerCase())).orElse("");
        controllerPath = controllerPath.substring(0, 1).toLowerCase() + controllerPath.substring(1);

        StringBuilder content = new StringBuilder();
        content.append("@RequestMapping(\"")
                .append(prefix)
                .append(controllerPath)
                .append("\")")
                .append("@RestController");

        if (useAPI) {
            content.append("@Api(tags = \"")
                    .append(entityName)
                    .append("控制器\")");
        }

        content.append(" public class ")
                .append(entityName)
                .append(suffix);

        baseClassOptional.ifPresent(psiClass -> content.append(" extends ")
                .append(psiClass.getName()));

        Optional<PsiClass> pBaseClassOptional = baseClassOptional;

        String entityFieldName = MyStringUtils.firstLetterToLower(entityName);

        content.append("{")
                .append("@Resource private ")
                .append(entityClasses.getServiceClass().getName())
                .append(" ")
                .append(entityFieldName)
                .append("Service; ")
                .append("@ApiOperation(\"保存\") @PostMapping(\"/save\")")
                .append("public void save(@RequestBody  ").append(entityClasses.getDtoClass().getName()).append(" ").append(entityFieldName).append(") { ")
                .append(entityFieldName).append("Service.save(").append(entityFieldName).append("); }")
                .append("@ApiOperation(\"根据主键删除\")  @DeleteMapping(\"/delete/{id}\") public void delete(@PathVariable(\"id\") Long id) {").append(
                entityFieldName).append("Service.delete(id);}")
                .append("@ApiOperation(\"查找所有数据\") @GetMapping(\"/list\") public List<").append(entityClasses.getDtoClass().getName()).append(
                "> list() { return ").append(entityFieldName).append("Service.findAll(); }")
                .append("@ApiOperation(\"分页查询\") @PostMapping(\"/page-query\") public PageInfo<").append(entityClasses.getDtoClass().getName()).append(
                "> pageQuery(@RequestBody ")
                .append(entityClasses.getQueryClass().getName()).append(" query) { return ").append(entityFieldName).append(
                "Service.pageQuery(query);}")
                .append("}");

        // 在controller目录下创建Controller
        ClassCreator.of(project)
                .init(entityClasses.getEntityName() + suffix, content.toString())
                .importClass("javax.annotation.Resource")
                .importClass("org.springframework.web.bind.annotation.RequestMaping")
                .importClass("org.springframework.web.bind.annotation.PostMapping")
                .importClass("GetMapping")
                .importClass("DeleteMapping")
                .importClass("RequestBody")
                .importClass("io.swagger.annotations.Api")
                .importClass("io.swagger.annotations.ApiOperation")
                .importClass("java.util.List")
                .importClass("PathVariable")
                .importClass("RequestParam")
                .importClass("com.github.pagehelper.PageInfo")
                .importClassIf(() -> pBaseClassOptional.get().getName(), pBaseClassOptional::isPresent)
                .addTo(controllerDirectory)
                .and(controllerClass -> {
                    psiUtils.importClass(controllerClass, entityClasses.getDtoClass(), entityClasses.getServiceClass(),
                            entityClasses.getQueryClass());
                });
    }

    /**
     * 创建Repository
     *
     * @param aClass 实体类名称
     */
    private void createRepository(EntityClasses entityClasses) {
        String entityName = entityClasses.getEntityName();
        assert entityName != null;
        PsiDirectory parentDirectory = containerDirectory.getParentDirectory();
        PsiDirectory repositoryDirectory = findDir("repository").orElseGet(() -> null == parentDirectory ?
                containerDirectory : psiUtils.getOrCreateSubDirectory(parentDirectory, "repository"));

        String repositoryName = entityName.replace("Entity", "").concat("Repository");
        getBaseRepositoryClass(repositoryDirectory, baseRepositoryClass ->
                ClassCreator.of(project).init(repositoryName,
                        "public interface " + repositoryName + " extends BaseRepository<" + entityClasses.getEntityClassName() + "> {}")
                        .importClass(entityClasses.getEntityClass())
                        .importClass(baseRepositoryClass)
                        .addTo(repositoryDirectory)
                        .and(repositoryClass -> createClasses(entityClasses.setRepositoryClass(repositoryClass))));
    }

    /**
     * 获取BaseRepository，如果没有这个类则创建一个
     *
     * @return 获取或创建的BaseRepository
     */
    private void getBaseRepositoryClass(
            PsiDirectory repositoryDiresctory,
            Consumer<PsiClass> consumer) {
        // 获取BaseRepository，如果BaseRepository为空，则创建一个BaseRepository
        Optional<PsiClass> baseRepositoryClassOptional = psiUtils.findClass("BaseRepository");
        if (baseRepositoryClassOptional.isPresent()) {
            consumer.accept(baseRepositoryClassOptional.get());
            return;
        }

        ClassCreator.of(project).init("BaseRepository",
                "@NoRepositoryBean public interface BaseRepository<E> extends JpaRepository<E, Long>, JpaSpecificationExecutor<E> {}")
                .importClass("NoRepositoryBean")
                .importClass("JpaRepository")
                .importClass("JpaSpecificationExecutor")
                .addTo(repositoryDiresctory)
                .and(consumer);
    }

    private static class EntityClasses {
        private PsiClass entityClass;
        private PsiClass repositoryClass;
        private PsiClass mapperClass;
        private PsiClass dtoClass;
        private PsiClass serviceClass;
        private PsiClass serviceImplClass;
        private PsiClass controllerClass;
        private PsiDirectory serviceDirectory;
        private PsiDirectory queryDirectory;
        private PsiClass queryClass;
        private PsiClass daoClass;

        PsiClass getEntityClass() {
            return entityClass;
        }

        EntityClasses setEntityClass(PsiClass entityClass) {
            this.entityClass = entityClass;
            return this;
        }

        PsiClass getRepositoryClass() {
            return repositoryClass;
        }

        EntityClasses setRepositoryClass(PsiClass repositoryClass) {
            this.repositoryClass = repositoryClass;
            return this;
        }

        PsiClass getMapperClass() {
            return mapperClass;
        }

        EntityClasses setMapperClass(PsiClass mapperClass) {
            this.mapperClass = mapperClass;
            return this;
        }

        PsiClass getDtoClass() {
            return dtoClass;
        }

        EntityClasses setDtoClass(PsiClass dtoClass) {
            this.dtoClass = dtoClass;
            return this;
        }

        PsiClass getServiceClass() {
            return serviceClass;
        }

        EntityClasses setServiceClass(PsiClass serviceClass) {
            this.serviceClass = serviceClass;
            return this;
        }

        public PsiClass getServiceImplClass() {
            return serviceImplClass;
        }

        public EntityClasses setServiceImplClass(PsiClass serviceImplClass) {
            this.serviceImplClass = serviceImplClass;
            return this;
        }

        public PsiClass getControllerClass() {
            return controllerClass;
        }

        public EntityClasses setControllerClass(PsiClass controllerClass) {
            this.controllerClass = controllerClass;
            return this;
        }

        PsiDirectory getServiceDirectory() {
            return serviceDirectory;
        }

        EntityClasses setServiceDirectory(PsiDirectory serviceDirectory) {
            this.serviceDirectory = serviceDirectory;
            return this;
        }

        String getEntityName() {
            return Objects.requireNonNull(this.getEntityClass().getName()).replace("Entity", "");
        }

        String getEntityClassName() {
            return this.entityClass.getName();
        }

        PsiClass getQueryClass() {
            return queryClass;
        }

        EntityClasses setQueryClass(PsiClass queryClass) {
            this.queryClass = queryClass;
            return this;
        }

        PsiClass getDaoClass() {
            return daoClass;
        }

        EntityClasses setDaoClass(PsiClass daoClass) {
            this.daoClass = daoClass;
            return this;
        }

        PsiDirectory getQueryDirectory() {
            return queryDirectory;
        }

        void setQueryDirectory(PsiDirectory queryDirectory) {
            this.queryDirectory = queryDirectory;
        }
    }

    public static void main(String[] args) {
        System.out.println("test\"".replace("\"", ""));
    }
}