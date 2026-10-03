package com.example.uptime.aggregation.infrastructure.persistence;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Configuration(proxyBeanMethods = false)
public class UptimeJsonConfiguration {

	@Bean
	public PersistenceJson persistenceJson(ObjectMapper mapper) {
		return new PersistenceJson(mapper);
	}

	@Bean
	public HibernatePropertiesCustomizer uptimeJsonFormatMapper(PersistenceJson json) {
		return properties -> properties.put("hibernate.type.json_format_mapper", json);
	}

	/** Uses Boot's modules, but pins temporal JSON to lossless ISO-8601 strings. */
	public static final class PersistenceJson implements FormatMapper {
		private final ObjectMapper mapper;

		public PersistenceJson(ObjectMapper mapper) {
			this.mapper = mapper.rebuild().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS,
								DateTimeFeature.TRUNCATE_TO_MSECS_ON_WRITE, DateTimeFeature.TRUNCATE_TO_MSECS_ON_READ).build();
		}

		public String write(Object value) {
			return mapper.writeValueAsString(value);
		}

		public <T> T read(String value, Class<T> type) {
			return mapper.readValue(value, type);
		}

		/** Immutable event comparison excludes children, which are normalized FK rows. */
		public String eventPayload(com.example.uptime.aggregation.domain.UptimeEvent event) {
			var tree = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(event);
			tree.remove("badEvents");
			return mapper.writeValueAsString(tree);
		}

		@Override
		public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
			return write(value);
		}

		@Override
		public <T> T fromString(CharSequence value, JavaType<T> javaType, WrapperOptions options) {
			return mapper.readValue(value.toString(), mapper.constructType(javaType.getJavaType()));
		}
	}
}
